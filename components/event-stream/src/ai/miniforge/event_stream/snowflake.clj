;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.event-stream.snowflake
  "Twitter-Snowflake-style sortable event-id generator (BD-2b RFC v3/v4).

   Layout (64-bit):
     - 41-bit UTC ms timestamp from a fixed miniforge epoch
       (2026-01-01T00:00:00Z),
     - 10-bit local worker id (0..1023),
     - 12-bit per-ms sequence (0..4095).

   Two surface forms:

     (next-id! generator)        ;; -> java.util.UUID
     (next-id-hex! generator)    ;; -> 16-char lowercase hex string

   The UUID encodes the snowflake into its most-significant 64 bits
   and leaves the low 64 bits zero. This keeps `:event/id` a uuid?
   for downstream code (schemas, accumulators, existing tests) while
   the leading hex of the UUID's string form is the sortable
   snowflake. Filenames carry just the hex form for compactness.

   UUID hex form sorts lexically by creation order across all workflows
   on a single host.

   Worker-id allocation is lease-based under
   `~/.miniforge/events/.workers/{id}.lease` using a Java FileLock.
   The OS releases the lock automatically on JVM exit (graceful or
   crash), so stale leases are reclaimed without explicit teardown.
   The lease *file* persists with old metadata between processes; only
   the lock state matters.

   Clock rollback is handled in-memory: the generator refuses to emit
   an id whose composed value would sort before the last-emitted id,
   and stalls until the wall clock catches up. A logged warning surfaces
   the rollback. Persisting a high-water mark to disk for cross-restart
   safety is deferred — the cost is a write-per-id, and the actual
   collision risk requires a JVM crash + restart inside the same
   millisecond + same sequence slot, which is narrow."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.response.interface :as response]
   [clojure.java.io :as io]
   [ai.miniforge.logging.interface :as log]))

;; NOTE: The worker-id lease path constructs java.io.RandomAccessFile,
;; calls .getChannel (FileChannel), .tryLock (returning FileLock), and
;; wraps bytes via java.nio.ByteBuffer. Those references appear FQN
;; inside fn bodies rather than imported at the top, so the namespace
;; loads in Babashka (which doesn't ship java.nio.channels.FileLock).
;; Worker-id leasing is JVM-only — the BB-runtime test loads the
;; namespace but never calls it, keeping event-stream BB-compatible.

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const ^long miniforge-epoch-ms
  "ms since unix epoch for 2026-01-01T00:00:00Z. The 41-bit timestamp
   field is computed as (now - epoch). 41 bits ≈ 69.7 years from this
   epoch — comfortable headroom."
  1767225600000)

(def ^:const ^long max-seq
  "12-bit sequence — 4096 distinct ids per (ms × worker)."
  4095)

(def ^:const ^long max-workers
  "10-bit worker space — 1024 concurrent workers per host."
  1024)

(def ^:const ^long worker-shift 12)
(def ^:const ^long ts-shift 22)

;------------------------------------------------------------------------------ Layer 0
;; Pure id-composition

(defn compose-id
  "Compose a 64-bit snowflake from (ts-since-epoch-ms, worker-id, seq).
   Pure: no clock or state."
  ^long [^long ts-since-epoch ^long worker ^long sequence]
  (bit-or (bit-shift-left ts-since-epoch ts-shift)
          (bit-shift-left worker worker-shift)
          sequence))

(defn format-hex
  "Render a 64-bit id as a 16-char lowercase hex string."
  ^String [^long id]
  (format "%016x" id))

(defn long->uuid
  "Embed a 64-bit snowflake id into a `java.util.UUID` as the
   most-significant bits, with the low 64 bits zero. Keeps `:event/id`
   typed as uuid for existing consumers while making the leading hex
   the sortable snowflake."
  ^java.util.UUID [^long id]
  (java.util.UUID. id 0))

(defn uuid->hex
  "Extract the 16-char lowercase hex of the snowflake bits from a UUID
   created by `long->uuid`. Inverse of long->uuid for filename use."
  ^String [^java.util.UUID uuid]
  (format-hex (.getMostSignificantBits uuid)))

;------------------------------------------------------------------------------ Layer 0
;; Generator state

(defn- initial-state
  [worker-id]
  {:last-ts -1
   :last-seq -1
   :worker-id worker-id})

(defn- next-state
  "Pure: compute the next state from `state` and the current wall ms.
   Returns either a new state (advanced) or one of the sentinels:
     ::pre-epoch        wall ms is before the miniforge epoch — the
                        timestamp field would be negative and the bit
                        layout would corrupt
     ::clock-rollback   wall ms is before last-ts → caller must stall
     ::seq-exhausted    same ms but seq already at max → caller must stall

   Time is encoded relative to `miniforge-epoch-ms`."
  [{:keys [^long last-ts ^long last-seq] :as state} ^long now-ms]
  (cond
    (< now-ms miniforge-epoch-ms) ::pre-epoch
    (< now-ms last-ts)            ::clock-rollback
    (== now-ms last-ts)
    (if (< last-seq max-seq)
      (assoc state :last-seq (inc last-seq))
      ::seq-exhausted)
    :else
    (assoc state :last-ts now-ms :last-seq 0)))

;------------------------------------------------------------------------------ Layer 0
;; Worker-id leasing (FileLock-based)

(defn- default-workers-dir
  []
  (io/file (config/miniforge-home) "events" ".workers"))

(defn- lease-io-anomaly
  [workers-dir lease-path id error]
  (cond-> (response/make-anomaly
           :anomalies/unavailable
           (messages/t :snowflake/lease-io-failure
                       {:worker-id id
                        :lease-path (.getAbsolutePath lease-path)})
           {:reason :lease-io-failure
            :worker-id id
            :lease-path (.getAbsolutePath lease-path)
            :workers-dir (.getAbsolutePath workers-dir)})
    error (assoc :anomaly/cause (ex-message error))))

(defn- workers-exhausted-anomaly
  [workers-dir]
  (response/make-anomaly
   :anomalies/busy
   (messages/t :snowflake/workers-exhausted
               {:max max-workers})
   {:reason :workers-exhausted
    :max    max-workers
    :workers-dir (.getAbsolutePath workers-dir)}))

(defn- write-lease-metadata!
  "Truncate `channel` and write the worker-id + acquire timestamp as
   EDN. Force to disk. Metadata is informational only — the OS-level
   FileLock release on JVM exit is what guarantees correctness, so
   pid/host fields are not load-bearing and are deliberately omitted
   to keep this namespace Babashka-loadable
   (java.lang.management is not in BB)."
  [channel worker-id]
  (.truncate channel 0)
  (let [meta  {:worker-id   worker-id
               :acquired-at (System/currentTimeMillis)}
        bytes (.getBytes (str (pr-str meta) "\n") "UTF-8")]
    (.write channel (java.nio.ByteBuffer/wrap bytes))
    (.force channel false)))

(defn- try-acquire-slot
  "Try to acquire an exclusive FileLock on `{workers-dir}/{id}.lease`.
   Returns a map `{:channel ... :lock ... :worker-id id}` on success,
   `nil` if the slot is held by another holder. Does not retry.

   Treats only lock-contention as a slot-held signal:
     - `.tryLock` returning nil → another process / JVM holds it.
     - `OverlappingFileLockException` → this JVM already holds an
       overlapping lock on the same file (e.g. a sibling generator
       in the same process).

   IO/permission failures (failed to create the lease file, no write
   permission on the workers dir) are real problems and propagate
   with workers-dir/id context attached, rather than silently moving
   to the next slot — which would otherwise misleadingly throw
   `:workers-exhausted` even when no slots are actually contended."
  [workers-dir ^long id]
  (.mkdirs workers-dir)
  (let [lease-path (io/file workers-dir (str id ".lease"))]
    ;; Resolve OverlappingFileLockException lazily via Class/forName so
    ;; this namespace stays loadable in Babashka (which doesn't ship
    ;; java.nio.channels). A typed `(catch java.nio.channels...)` would
    ;; force compile-time class resolution and break BB load.
    (try
      (let [raf     (java.io.RandomAccessFile. lease-path "rw")
            channel (.getChannel raf)]
        (try
          (let [lock (.tryLock channel)]
            (if lock
              (do
                (write-lease-metadata! channel id)
                {:channel channel :lock lock :worker-id id :raf raf})
              (do (.close raf) nil)))
          (catch Exception e
            (try (.close raf) (catch Exception _))
            (if (instance? (Class/forName "java.nio.channels.OverlappingFileLockException") e)
              ;; This JVM already holds an overlapping lock on the same
              ;; file. Treat as "slot held" and let the caller advance.
              nil
              ;; Real IO/permission failure. Return an explicit anomaly
              ;; rather than silently masking it as :workers-exhausted later.
              (lease-io-anomaly workers-dir lease-path id e)))))
      (catch Exception e
        (lease-io-anomaly workers-dir lease-path id e)))))

(defn acquire-worker-lease!
  "Walk slots 0..1023 and acquire the first whose lease file is
   unlocked.

   Why FileLock vs Clojure refs / STM
   ----------------------------------
   The lease has to coordinate across *operating-system processes*,
   not just threads inside one JVM. Multiple miniforge CLI invocations
   on the same host run as independent processes — they share the
   `~/.miniforge/events/.workers/` directory but no JVM-level state.
   Refs (`dosync`, `alter`, `commute`) are STM coordinated only within
   a single JVM, so two concurrent processes both running
   `(dosync (alter ref ...))` would hand each one the same worker id
   and produce colliding event-id streams downstream. OS-level file
   locks (`FileChannel/tryLock`) are the only primitive that gives
   cross-process exclusion on a vanilla single-machine setup. As a
   bonus the OS releases the lock automatically on JVM exit (graceful
   or crash), so stale leases reclaim themselves without explicit
   teardown.

   Returns a canonical anomaly when all slots are held — typical only
   when 1024+ miniforge processes coexist on the same host — or when
   lease-file I/O fails."
  [workers-dir]
  (loop [id 0]
    (cond
      (>= id max-workers)
      (workers-exhausted-anomaly workers-dir)
      :else
      (let [lease (try-acquire-slot workers-dir id)]
        (cond
          (response/anomaly-map? lease) lease
          lease lease
          :else (recur (inc id)))))))

(defn release-lease!
  "Release a worker-id lease. Idempotent. The OS would release on JVM
   exit anyway; this is for explicit teardown (tests, shutdown hooks)."
  [{:keys [lock channel raf]}]
  (when lock
    (try (.release lock) (catch Exception _)))
  (when channel
    (try (.close channel) (catch Exception _)))
  (when raf
    (try (.close raf) (catch Exception _))))

;------------------------------------------------------------------------------ Layer 1
;; Generator handle

(defn create-generator
  "Allocate a new snowflake generator. Acquires a worker-id lease under
   `:workers-dir` (default `~/.miniforge/events/.workers/`).

   Returns a map carrying:
     :state      atom with {:last-ts :last-seq :worker-id}
     :worker-id  long, 0..1023
     :lease      lease handle (release on close!)
     :now-fn     0-arg fn returning wall ms (override for tests)
     :logger     optional logger for clock-rollback warnings

   Tests can pass `:now-fn` and `:lease` directly to avoid filesystem
   side effects."
  [& [{:keys [workers-dir now-fn logger lease]
       :or   {now-fn (fn ^long [] (System/currentTimeMillis))}}]]
  (let [lease (or lease (acquire-worker-lease! (or workers-dir (default-workers-dir))))]
    (if (response/anomaly-map? lease)
      lease
      (let [worker-id (:worker-id lease)]
        {:state     (atom (initial-state worker-id))
         :worker-id worker-id
         :lease     lease
         :now-fn    now-fn
         :logger    logger}))))

(defn close!
  "Release the generator's worker-id lease. Idempotent."
  [generator]
  (when-let [lease (:lease generator)]
    (release-lease! lease)))

;------------------------------------------------------------------------------ Layer 1
;; Id generation

(defn- log-rollback!
  [logger details]
  (when logger
    (log/warn logger :event-stream :snowflake/clock-rollback
              {:message "Snowflake generator detected clock rollback; stalling"
               :data    details})))

(def ^:private ^:const ^long park-budget-nanos
  "Park budget for the wait loop on clock-rollback / sequence-exhausted.
   100µs is short enough to wake well before the next ms tick (so the
   loop spins ~10× per ms in the worst case) but long enough to avoid
   a CPU-burning busy-spin. `parkNanos` may wake earlier on signal —
   that's fine, the outer loop simply re-checks the wall clock."
  100000)

(def ^:private park-nanos-method
  "Lazily-resolved `java.util.concurrent.locks.LockSupport/parkNanos`
   via `Class/forName` + reflection. The id-generation path only
   executes on the JVM, but this namespace is transitively loaded
   from Babashka-runtime bricks and BB does not ship
   `java.util.concurrent.locks`. A static method call would force
   compile-time class resolution and break BB load; this delay
   defers it to the first JVM invocation."
  (delay
    (.getMethod (Class/forName "java.util.concurrent.locks.LockSupport")
                "parkNanos"
                (into-array Class [Long/TYPE]))))

(defn- park-briefly!
  "Park the calling thread for up to `park-budget-nanos`. Used by the
   id-generation loop in place of `Thread/sleep` so there's no
   millisecond-rounded delay and the call surface stays
   `LockSupport`-style rather than the deprecated sleep idiom. The
   thread can wake earlier on spurious signal; callers must re-check
   their condition."
  []
  (.invoke @park-nanos-method
           nil
           (into-array Object [(Long/valueOf park-budget-nanos)])))

(defn- pre-epoch-anomaly
  [now-ms worker-id]
  (response/make-anomaly
   :anomalies/incorrect
   (messages/t :snowflake/pre-epoch-clock
               {:now-ms now-ms :epoch-ms miniforge-epoch-ms})
   {:reason     :pre-epoch-clock
    :now-ms     now-ms
    :epoch-ms   miniforge-epoch-ms
    :worker-id  worker-id}))

(defn- handle-rollback!
  "Side-effect: warn once on first rollback observation. Park before
   the outer loop retries. Returns the new `warned?` value."
  [{:keys [logger ^long worker-id]} now-ms last-ts warned?]
  (when (not warned?)
    (log-rollback! logger {:now-ms    now-ms
                           :last-ts   last-ts
                           :worker-id worker-id}))
  (park-briefly!)
  true)

(defn- emit-id
  "CAS the new state and return the composed id long. nil on CAS loss
   (caller retries the outer loop)."
  [state old new-state ^long worker-id]
  (when (compare-and-set! state old new-state)
    (compose-id (- ^long (:last-ts new-state) miniforge-epoch-ms)
                worker-id
                ^long (:last-seq new-state))))

(defn next-id-long!
  "Internal: generate the next snowflake as a long. Parks on
   clock rollback or sequence exhaustion (≤100µs per retry via
   `LockSupport/parkNanos` rather than `Thread/sleep`).
   Public id functions (`next-id!`, `next-id-hex!`) wrap this.

   Returns a canonical anomaly when the wall clock is before the
   miniforge epoch."
  [{:keys [state now-fn ^long worker-id] :as gen}]
  (loop [warned? false]
    (let [now-ms (long (now-fn))
          old    @state
          r      (next-state old now-ms)]
      (cond
        (= r ::pre-epoch)        (pre-epoch-anomaly now-ms worker-id)
        (= r ::clock-rollback)   (recur (handle-rollback! gen now-ms (:last-ts old) warned?))
        (= r ::seq-exhausted)    (do (park-briefly!) (recur warned?))
        :else
        (or (emit-id state old r worker-id)
            ;; Lost CAS race against another thread — retry with fresh state.
            (recur warned?))))))

(defn next-id!
  "Generate the next snowflake event id as a `java.util.UUID`. The
   snowflake bits live in the UUID's most-significant 64 bits; low 64
   bits are zero. The UUID's string form sorts lexically by creation
   order on a single host."
  [generator]
  (let [id (next-id-long! generator)]
    (if (response/anomaly-map? id)
      id
      (long->uuid id))))

(defn next-id-hex!
  "Generate the next snowflake event id as a 16-char lowercase hex
   string. Use this for filenames and other places where the UUID's
   hyphens are inconvenient."
  [generator]
  (let [id (next-id-long! generator)]
    (if (response/anomaly-map? id)
      id
      (format-hex id))))
