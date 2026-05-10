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

(ns ai.miniforge.event-stream.manifest
  "Per-workflow manifest: lifecycle state, archive state, snapshot
   watermark, owner lease (BD-2b sub-2 of the RFC at
   `docs/design/event-stream-replay-retention.md`).

   The manifest is the keystone for BD-2b/2c. It carries:
     - workflow lifecycle  (`status`)
     - storage lifecycle   (`archive_status`)
     - snapshot availability (`snapshot_status`)
     - snapshot watermark  (workflow_sequence_number + last_event_id)
     - owner lease         (pid / host / heartbeat / TTL)

   Sub-3 (atomic archive) and BD-2c (snapshots, consumer hydration)
   read/write the same shape. The cross-repo JSON Schema fixture lives
   at `contracts/event-stream/manifest.schema.json` so the Rust
   consumer can decode the same on-disk form without us re-deriving
   the contract.

   JVM-only: writes JSON via `cheshire`, reads pid/host via
   `java.lang.management` and `java.net`, and runs heartbeats on a
   `ScheduledExecutorService`. The BB-runtime CLI never touches this
   namespace — it's used by the workflow runner (lifecycle hooks) and
   by the cleanup path (sub-3) which both run on the JVM."
  {:miniforge/runtime :jvm-only}
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m])
  (:import
   [java.io File]
   [java.lang.management ManagementFactory]
   [java.net InetAddress]
   [java.nio.file Files StandardCopyOption]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]
   [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults — enums, filenames, operator knobs all loaded from
;; `resources/config/event-stream/manifest-defaults.edn`. Pulling them
;; out keeps the contract values in one place visible to both the
;; Malli schema below and the cross-repo JSON Schema fixture at
;; `contracts/event-stream/manifest.schema.json`, and lets operators
;; tune the runtime knobs (TTL, heartbeat period) without code edits.

(def defaults
  "Manifest defaults loaded from
   `resources/config/event-stream/manifest-defaults.edn` at namespace
   load. Keys: `:schema-version`, `:manifest-filename`,
   `:workflow-statuses`, `:archive-statuses`, `:snapshot-statuses`,
   `:default-lease-ttl-seconds`, `:heartbeat-period-seconds`."
  (-> (io/resource "config/event-stream/manifest-defaults.edn")
      slurp
      edn/read-string))

(def schema-version       (:schema-version defaults))
(def manifest-filename    (:manifest-filename defaults))
(def workflow-statuses    (:workflow-statuses defaults))
(def archive-statuses     (:archive-statuses defaults))
(def snapshot-statuses    (:snapshot-statuses defaults))
(def default-lease-ttl-seconds (:default-lease-ttl-seconds defaults))
(def heartbeat-period-seconds  (:heartbeat-period-seconds defaults))

;------------------------------------------------------------------------------ Layer 0
;; Malli schema — mirrors the RFC v4 shape and the cross-repo
;; JSON Schema at `contracts/event-stream/manifest.schema.json`.

(def ^:private snowflake-event-id-re
  "16-char lowercase hex per the BD-2b sub-1 Snowflake filename
   grammar. The cross-repo JSON Schema validates the same shape."
  #"^[0-9a-f]{16}$")

(def Manifest
  "Open Malli schema for the per-workflow manifest map. Open: future
   keys (e.g. additional snapshot-status detail) pass through without
   a schema bump per the RFC's forward-compatibility rule. Constraints
   mirror `contracts/event-stream/manifest.schema.json` so the Clojure
   producer and the eventual Rust consumer can't drift on validation."
  [:map
   [:workflow_id :uuid]
   [:schema_version [:string {:min 1}]]
   [:status (into [:enum] workflow-statuses)]
   [:archive_status (into [:enum] archive-statuses)]
   [:snapshot_status (into [:enum] snapshot-statuses)]
   [:snapshot_failure_reason {:optional true} [:maybe :string]]
   [:created_at :string]
   [:completed_at {:optional true} [:maybe :string]]
   [:archived_at {:optional true} [:maybe :string]]
   [:snapshot_watermark
    [:map
     [:workflow_sequence_number [:int {:min 0}]]
     [:last_event_id {:optional true} [:maybe [:re snowflake-event-id-re]]]]]
   [:raw_events_retained :boolean]
   [:raw_events_retained_until {:optional true} [:maybe :string]]
   [:owner
    [:map
     [:pid [:string {:min 1}]]
     [:host [:string {:min 1}]]
     [:lease_renewed_at :string]
     [:lease_ttl_seconds [:int {:min 1}]]]]])

(defn valid?
  "True if `m` matches the manifest schema."
  [m]
  (m/validate Manifest m))

(defn explain
  "Return a malli explanation when `m` does not match. nil when valid."
  [m]
  (m/explain Manifest m))

;------------------------------------------------------------------------------ Layer 0
;; State machine

(def ^:private legal-archive-transitions
  "Forward-only edges. Backwards moves (e.g. archived → live) are
   never legal — once events have been moved they don't come back."
  {:live       #{:archiving}
   :archiving  #{:archived :live}        ; rollback to :live on aborted archive
   :archived   #{:tombstoned}
   :tombstoned #{}})

(defn legal-archive-transition?
  "True iff the manifest may move from `from` to `to`."
  [from to]
  (boolean (contains? (get legal-archive-transitions from #{}) to)))

(defn transition-archive
  "Pure: move the manifest's `archive_status` from its current value
   to `to`, returning the updated manifest. Throws ex-info on illegal
   moves so callers can't silently corrupt the state machine."
  [manifest to]
  (let [from (:archive_status manifest)]
    (if (legal-archive-transition? from to)
      (assoc manifest :archive_status to)
      (throw (ex-info "Illegal archive_status transition"
                      {:reason :illegal-archive-transition
                       :from   from
                       :to     to
                       :workflow-id (:workflow_id manifest)})))))

;------------------------------------------------------------------------------ Layer 0
;; Owner / lease primitives

(defn- ^String iso-now
  "ISO-8601 instant for `lease_renewed_at` / `created_at` etc."
  []
  (str (Instant/now)))

(defn- ^String pid-string
  "Process id as a string. Built from the JMX runtime bean name —
   `pid@host` on hotspot — so we don't need Java-9-only APIs."
  []
  (let [name (.getName (ManagementFactory/getRuntimeMXBean))]
    (first (str/split name #"@"))))

(defn- ^String hostname-string
  "Best-effort canonical hostname. Falls back to `\"unknown-host\"` if
   DNS / lookup fails — the field is informational, not load-bearing."
  []
  (try (.getCanonicalHostName (InetAddress/getLocalHost))
       (catch Exception _ "unknown-host")))

(defn fresh-owner
  "Build an owner map with the calling process's pid + host, marking
   the lease as renewed right now."
  ([] (fresh-owner default-lease-ttl-seconds))
  ([ttl-seconds]
   {:pid               (pid-string)
    :host              (hostname-string)
    :lease_renewed_at  (iso-now)
    :lease_ttl_seconds ttl-seconds}))

(defn renew-lease
  "Pure: update `manifest`'s `:owner.lease_renewed_at` to now. Does
   not change pid/host/ttl — those are stamped at acquisition and
   only re-stamped when the workflow is re-claimed."
  [manifest]
  (assoc-in manifest [:owner :lease_renewed_at] (iso-now)))

(defn lease-fresh?
  "True if `manifest`'s lease was renewed within `ttl_seconds` of `now`.
   Pure — caller passes `now` as an `Instant` for testability."
  ([manifest] (lease-fresh? manifest (Instant/now)))
  ([manifest ^Instant now]
   (let [renewed (Instant/parse ^String (get-in manifest [:owner :lease_renewed_at]))
         ttl-s   (long (get-in manifest [:owner :lease_ttl_seconds]))
         age-s   (- (.getEpochSecond now) (.getEpochSecond renewed))]
     (<= age-s ttl-s))))

(defn owner-pid-alive?
  "True if the manifest's `:owner.pid` corresponds to a running
   process on this host. Use `ProcessHandle/of` (Java 9+). Returns
   false when host doesn't match — cross-host pid checks are out of
   scope (single-machine non-goal per the RFC)."
  [manifest]
  (let [owner-host (get-in manifest [:owner :host])]
    (and (= owner-host (hostname-string))
         (try
           (let [pid (Long/parseLong (get-in manifest [:owner :pid]))]
             (.isPresent (java.lang.ProcessHandle/of pid)))
           (catch Exception _ false)))))

;------------------------------------------------------------------------------ Layer 0
;; Manifest construction

(defn init-active
  "Build a fresh `:active` / `:live` manifest for a new workflow.
   Owner is the calling process. Snapshot watermark starts at
   sequence 0 with no event id — sub-3 / BD-2c update it as
   checkpoints land."
  [workflow-id & [{:keys [ttl-seconds] :or {ttl-seconds default-lease-ttl-seconds}}]]
  {:workflow_id              workflow-id
   :schema_version           schema-version
   :status                   :active
   :archive_status           :live
   :snapshot_status          :none
   :snapshot_failure_reason  nil
   :created_at               (iso-now)
   :completed_at             nil
   :archived_at              nil
   :snapshot_watermark       {:workflow_sequence_number 0
                              :last_event_id            nil}
   :raw_events_retained      true
   :raw_events_retained_until nil
   :owner                    (fresh-owner ttl-seconds)})

(defn mark-terminal
  "Pure: set `status` to a terminal value (`:completed | :failed |
   :cancelled`) and stamp `completed_at`. Does NOT change
   archive_status — that's a separate state machine driven by sub-3."
  [manifest status]
  (assert (contains? #{:completed :failed :cancelled} status)
          (str "mark-terminal expects a terminal status, got " status))
  (assoc manifest
         :status       status
         :completed_at (iso-now)))

;------------------------------------------------------------------------------ Layer 0
;; File IO — atomic write via temp + Files/move(ATOMIC_MOVE)

(defn manifest-path
  "Return the manifest.json path under `workflow-dir`. The caller
   chooses live/{wid} vs archived/{wid} — manifest is the same shape
   in both."
  ^File [workflow-dir]
  (io/file workflow-dir manifest-filename))

(defn load-manifest
  "Read and parse a manifest from disk. Returns the parsed map (with
   keyword keys) or nil when the file is absent. Throws if present
   but unparseable — a corrupted manifest is a real bug, not a
   recoverable signal."
  [workflow-dir]
  (let [^File f (manifest-path workflow-dir)]
    (when (.exists f)
      (let [raw (json/parse-string (slurp f) true)]
        (cond-> raw
          ;; cheshire returns strings for the workflow_id; restore the UUID.
          (string? (:workflow_id raw))
          (update :workflow_id #(java.util.UUID/fromString %))
          ;; status / archive_status / snapshot_status come back as strings.
          (string? (:status raw))          (update :status keyword)
          (string? (:archive_status raw))  (update :archive_status keyword)
          (string? (:snapshot_status raw)) (update :snapshot_status keyword))))))

(defn save-manifest!
  "Atomically write `manifest` to `workflow-dir/manifest.json`.

   Writes to `manifest.json.tmp` first, fsyncs, then `Files/move`s
   with `ATOMIC_MOVE` + `REPLACE_EXISTING`. A reader during the move
   sees either the old file or the new — never a half-written one.

   Throws on schema-validation failure so callers can't silently
   persist a corrupt shape."
  [workflow-dir manifest]
  (when-let [err (explain manifest)]
    (throw (ex-info "Manifest fails schema validation"
                    {:reason :invalid-manifest
                     :workflow-dir (.getAbsolutePath ^File (io/file workflow-dir))
                     :error err})))
  (let [^File dir   (io/file workflow-dir)
        _           (.mkdirs dir)
        ^File final (manifest-path dir)
        ^File tmp   (io/file dir (str manifest-filename ".tmp"))]
    ;; Cheshire writes keyword keys via `name`, so the snake_case keys
    ;; in the manifest map (e.g. `:workflow_id`) round-trip as
    ;; `"workflow_id"` on disk. Keyword *values* (status enums, the
    ;; UUID workflow id) need explicit conversion before serialization
    ;; — the `update`s below stringify them; `load-manifest` reverses
    ;; the conversions on read.
    (with-open [w (io/writer tmp)]
      (json/generate-stream
        (-> manifest
            (update :workflow_id str)
            (update :status name)
            (update :archive_status name)
            (update :snapshot_status name))
        w
        {:pretty true}))
    ;; fsync the tmp file before moving so a crash mid-rename doesn't
    ;; leave an empty manifest on disk.
    (with-open [raf (java.io.RandomAccessFile. tmp "rw")]
      (.force (.getChannel raf) true))
    (Files/move (.toPath tmp)
                (.toPath final)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    final))

(defn renew-lease!
  "Re-stamp the lease and write the manifest atomically. Returns the
   updated manifest. Idempotent — a missing manifest is a no-op
   returning nil so the heartbeat doesn't crash if the workflow is
   archived between ticks."
  [workflow-dir]
  (when-let [m (load-manifest workflow-dir)]
    (let [renewed (renew-lease m)]
      (save-manifest! workflow-dir renewed)
      renewed)))

;------------------------------------------------------------------------------ Layer 1
;; Heartbeat — periodic lease renewal while the workflow is live.

(defn- default-heartbeat-error-log!
  "Last-resort logging when no `:on-error` callback is supplied. Writes
   a single-line warning to stderr so persistent IO failures during
   the heartbeat are observable in the operator's terminal / CI logs
   rather than silently disappearing. The workflow runner is the
   normal caller and should prefer to wire its own logger via
   `:on-error`."
  [^Throwable t workflow-dir]
  (binding [*out* *err*]
    (println (str "WARNING: manifest heartbeat renew failed for "
                  (.getAbsolutePath ^File (io/file workflow-dir))
                  ": " (.getMessage t)))))

(defn ^ScheduledExecutorService start-heartbeat!
  "Start a single-threaded scheduled executor that renews the
   manifest's lease every `:period-seconds` (default
   `heartbeat-period-seconds`). Returns the executor handle for
   `stop-heartbeat!`.

   Renew failures: a transient IO error during a beat must not crash
   the workflow runner. By default any caught Throwable is reported
   via `default-heartbeat-error-log!` (a stderr warning) so persistent
   failures are visible. Callers that have a structured logger
   should pass `:on-error` instead — that callback fully replaces the
   default and is the recommended path for production wiring.

   The sub-3 cleanup path's `lease-fresh?` check tolerates a single
   missed renew via the TTL > period default, so a one-off swallowed
   error does not falsely flag the workflow as crashed."
  ([workflow-dir]
   (start-heartbeat! workflow-dir {}))
  ([workflow-dir {:keys [period-seconds on-error]
                  :or   {period-seconds heartbeat-period-seconds}}]
   (let [report-error (or on-error
                          #(default-heartbeat-error-log! % workflow-dir))
         ^ScheduledExecutorService ex
         (Executors/newSingleThreadScheduledExecutor
           (reify java.util.concurrent.ThreadFactory
             (newThread [_ r]
               (doto (Thread. r "miniforge-manifest-heartbeat")
                 (.setDaemon true)))))]
     (.scheduleAtFixedRate ex
                           ^Runnable
                           (fn []
                             (try (renew-lease! workflow-dir)
                                  (catch Throwable t (report-error t))))
                           (long period-seconds)
                           (long period-seconds)
                           TimeUnit/SECONDS)
     ex)))

(defn stop-heartbeat!
  "Shut the heartbeat executor down. Waits up to `:timeout-ms`
   (default 1000) for the in-flight renew to finish, then forces."
  ([^ScheduledExecutorService ex] (stop-heartbeat! ex {}))
  ([^ScheduledExecutorService ex {:keys [timeout-ms] :or {timeout-ms 1000}}]
   (when ex
     (.shutdown ex)
     (when-not (.awaitTermination ex (long timeout-ms) TimeUnit/MILLISECONDS)
       (.shutdownNow ex))
     nil)))
