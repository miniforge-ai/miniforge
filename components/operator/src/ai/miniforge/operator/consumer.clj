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
(ns ai.miniforge.operator.consumer
  "Operator-event consumer — Phase D D-2.

   Reads `:supervisory/intervention-requested` events that operator
   clients (the Rust control-plane-client, the dashboard) write as
   transit-JSON files into `{events-dir}/operator/`, validates them,
   and routes them through the bounded intervention lifecycle in
   `operator/intervention`.

   Contract (Phase D design decisions 3–4):

   - **Append-only consumption.** Operator event files are the audit
     stream; this consumer NEVER deletes them. Idempotency comes from
     a processed cursor persisted at `{events-dir}/operator/.processed`,
     keyed by intervention id (and by filename for files that carry no
     intervention).
   - **Nothing is silently discarded.** A file that cannot be parsed,
     or parses to an invalid intervention request, produces an
     `:operator/intervention-anomaly` event on the stream — visible in
     the same audit surface as everything else.
   - **Everything routes through the lifecycle.** Each accepted request
     is rebuilt via `intervention/create-intervention` (server-side
     authority — client-claimed state is not trusted), auto-approved
     when its request source is an operator-driven surface (the human
     IS the approver), and every state transition is published as a
     `:supervisory/intervention-state-changed` event. The
     supervisory-state accumulator materializes these and its emitter
     publishes the `:supervisory/intervention-upserted` snapshots the
     consoles render.
   - **Application is pluggable.** Actually acting on an approved
     intervention (control-state flags, resume machinery, degradation
     manager) is the D-3 application layer, injected as `:apply!`.
     Without it, accepted interventions honestly stop at `:approved` —
     the consumer never fakes `dispatched`/`applied` progress.

   Other event kinds legitimately live in the operator directory
   (meta-loop, reliability, degradation events); they are skipped and
   remembered in the cursor so each file is examined once."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.operator.messages :as messages]
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   [java.nio.channels FileChannel]
   [java.nio.file Files StandardCopyOption]
   [java.time Instant]
   [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

;; Constants
(def ^{:stratum 0} ^:const intervention-requested-event-type
  :supervisory/intervention-requested)

(def ^{:stratum 0} ^:const intervention-decision-event-type
  "Operator verdict on an intervention parked at `:pending-human`.

   The counterpart to `intervention-requested`: a delegated source
   (`:meta-agent`, `:api`) proposes, a human decides. Without this the
   approval gate is one-way — a delegated request parks forever and the
   operator has no channel to answer it."
  :supervisory/intervention-decision)

(def ^{:stratum 0} ^:const state-changed-event-type
  :supervisory/intervention-state-changed)

(def ^{:stratum 0} ^:const anomaly-event-type
  :operator/intervention-anomaly)

(def ^{:stratum 0} auto-approve-request-sources
  "Request sources whose interventions are auto-approved: the operator
   surfaces where a human performed the originating gesture, so a
   second approval step would approve the approver (Phase D decision 4,
   v1). `:cli` and `:dashboard` join `:tui` / `:native-app` with D-4,
   which routes the console's and the CLI's pause/resume/cancel
   controls through this channel — a click or a typed command is the
   same human gesture, and parking them at `:pending-human` with no
   approver UI would make the control silently do nothing.

   Auto-approving these leans on each surface carrying operator
   identity before it ever writes a request. `:tui` / `:native-app` /
   `:cli` are local processes with no network listener. `:dashboard`
   is HTTP-exposed, so it is only safe here because the dashboard's
   control endpoints now require an authenticated operator session even
   when browse-auth is disabled (issue #1460); an unauthenticated caller
   is refused at the HTTP boundary and never reaches this table.
   Delegated sources (`:meta-agent`, `:api`) are NOT here — they park at
   `:pending-human`."
  #{:cli :dashboard :native-app :tui})

(def ^{:stratum 0} ^:private cursor-file-name ".processed")

(def ^{:stratum 0} ^:private consumer-lock-file-name ".consumer.lock")

(def ^{:stratum 0} ^:private empty-cursor
  {:schema-version 2
   :processed-intervention-ids #{}
   :processed-files #{}
   ;; Interventions this consumer parked at `:pending-human`, by id.
   ;;
   ;; A decision arrives in a LATER pass than the request, and the
   ;; consumer holds no state between passes — so the intervention it
   ;; must transition has to be recoverable from disk. Keeping it beside
   ;; the cursor (rather than querying the accumulator) keeps the
   ;; consumer a pure function of its own directory, and the entry
   ;; leaves the moment a decision lands, so this cannot grow without
   ;; bound.
   :pending-interventions {}})

(def ^{:stratum 0} ^:private empty-pass-result
  {:routed 0 :skipped 0 :anomalies 0})

;; ── File enumeration + parsing ─────────────────────────────────────────────
(defn- ^{:stratum 0} list-event-files
  "Event files in `operator-dir`, sorted by filename. Both filename
   grammars sort by creation: the snowflake hex prefix lexically, the
   legacy shape via its timestamp prefix."
  [operator-dir]
  (let [^java.io.File d (io/file operator-dir)]
    (if (.isDirectory d)
      (->> (or (.listFiles d) [])
           (filter (fn [^java.io.File f]
                     (and (.isFile f)
                          (.endsWith (.getName f) ".json"))))
           (sort-by (fn [^java.io.File f] (.getName f)))
           vec)
      [])))

(defn- ^{:stratum 0} revive-uuid
  "Parse a UUID-shaped string back to a java.util.UUID. Non-string /
   unparseable values pass through unchanged."
  [v]
  (if (string? v) (or (parse-uuid v) v) v))

(defn- ^{:stratum 0} revive-inst
  "Parse an RFC-3339 string back to a java.util.Date. Non-string /
   unparseable values pass through unchanged."
  [v]
  (if (string? v)
    (try (java.util.Date/from (Instant/parse v))
         (catch Exception _e v))
    v))

(defn- ^{:stratum 0} valid-request-identities?
  "True when request identity fields have their governed runtime types."
  [event]
  (and (or (nil? (:event/id event))
           (uuid? (:event/id event)))
       (uuid? (:intervention/id event))
       (or (nil? (:workflow/id event))
           (uuid? (:workflow/id event)))))

(defn- ^{:stratum 0} remember-intervention-id
  [acc intervention-id]
  (update acc :cursor update :processed-intervention-ids conj intervention-id))

(defn- ^{:stratum 0} remember-parked
  "Record an intervention left at `:pending-human` so a later pass can
   act on the operator's decision. Any other terminal-for-now state
   needs no record — it has already moved on."
  [acc intervention]
  (if (= :pending-human (:intervention/state intervention))
    (update acc :cursor assoc-in
            [:pending-interventions (:intervention/id intervention)]
            intervention)
    acc))

(defn- ^{:stratum 0} forget-parked
  [acc intervention-id]
  (update acc :cursor update :pending-interventions dissoc intervention-id))

(defn- ^{:stratum 0} remember-file
  [acc file-name]
  (update acc :cursor update :processed-files conj file-name))

(defn- ^{:stratum 0} accept-every-request?
  [_event]
  true)

(defn- ^{:stratum 0} transition-succeeded?
  [result]
  (true? (:success? result)))

(defn- ^{:stratum 0} overlapping-file-lock?
  "Babashka does not expose OverlappingFileLockException as a resolvable
   catch class, so identify this one contention signal by its JVM class
   name and rethrow every other exception."
  [e]
  (= "java.nio.channels.OverlappingFileLockException"
     (.getName (class e))))

(defn- ^{:stratum 0} event->request
  "Extract the InterventionRequest fields from a parsed event map.
   Only the request-identity fields are trusted from the wire; state
   and timestamps are server-assigned by `create-intervention`."
  [event]
  (cond-> {:intervention/id (:intervention/id event)
           :intervention/type (:intervention/type event)
           :intervention/target-type (:intervention/target-type event)
           :intervention/target-id (:intervention/target-id event)
           :intervention/requested-by (:intervention/requested-by event)
           :intervention/request-source (:intervention/request-source event)}
    (:intervention/justification event)
    (assoc :intervention/justification (:intervention/justification event))

    (:intervention/details event)
    (assoc :intervention/details
           (dissoc (:intervention/details event) :failure/code))))

;; Event publication
(defn- ^{:stratum 0} intervention-workflow-id
  "The `:workflow/id` to stamp on lifecycle events — present only when
   the intervention targets a workflow, so its audit trail lands in the
   run's own event directory. Coerced to a UUID (target ids arrive as
   strings from the wire); an unparseable id yields nil rather than a
   string key that would fork sequence numbering / quiesce fencing
   away from the runner's UUID-keyed entries."
  [interv]
  (when (= :workflow (:intervention/target-type interv))
    (let [target-id (:intervention/target-id interv)]
      (if (uuid? target-id)
        target-id
        (some-> target-id str parse-uuid)))))

;; ── Polling lifecycle ──────────────────────────────────────────────────────
(def ^{:stratum 0} ^:const default-poll-interval-ms 1000)

(def ^{:stratum 0} ^:const initial-poll-delay-ms 0)

(defn- ^{:stratum 0} daemon-thread-factory
  "Named daemon threads for the poller (the repo's background-scheduler
   idiom — see event-stream `heartbeat.clj`): a consumer that is not
   stopped on some shutdown path must never keep the JVM alive."
  ^ThreadFactory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. ^Runnable runnable "miniforge-operator-consumer")
        (.setDaemon true)))))

(defn ^{:stratum 0} stop!
  "Stop a poller started by [[start!]]. Idempotent."
  [{:keys [^ScheduledExecutorService executor]}]
  (when executor
    (.shutdownNow executor))
  nil)

;------------------------------------------------------------------------------ Layer 1

;; ── Processed cursor — the idempotency manifest ────────────────────────────
(defn- ^{:stratum 1} cursor-file
  ^java.io.File [operator-dir]
  (io/file operator-dir cursor-file-name))

(defn- ^{:stratum 1} consumer-lock-file
  ^java.io.File [operator-dir]
  (io/file operator-dir consumer-lock-file-name))

(defn- ^{:stratum 1} revive-request-event
  "Restore the typed identity fields `read-event-file`'s tag stripping
   flattened to strings. Without this, republishing the event would
   violate the event-stream schema (`:event/id` uuid?, timestamps
   inst?) and re-serialize without `~u`/`~t` tags — and a string
   `:workflow/id` would fork per-workflow sequence numbering and
   quiesce fencing away from the UUID-keyed entries the runner uses."
  [event]
  (-> event
      (update :event/id revive-uuid)
      (update :intervention/id revive-uuid)
      (update :event/timestamp revive-inst)
      (update :intervention/requested-at revive-inst)
      (update :intervention/updated-at revive-inst)
      (cond->
        (contains? event :workflow/id)
        (update :workflow/id revive-uuid))))

(defn ^{:stratum 1} publish-state-changed!
  "Publish a `:supervisory/intervention-state-changed` event for
   `interv`. The single canonical emitter for lifecycle transitions —
   the application layer (D-3) publishes through here too, so every
   transition carries the same envelope shape."
  [stream interv]
  (let [envelope (es/create-envelope
                  stream
                  state-changed-event-type
                  (intervention-workflow-id interv)
                  (messages/t :consumer/state-changed
                              {:type (name (:intervention/type interv))
                               :state (name (:intervention/state interv))}))]
    (es/publish! stream (merge envelope interv))))

(defn- ^{:stratum 1} publish-anomaly!
  [stream file-name anomaly-data]
  (let [envelope (es/create-envelope
                  stream
                  anomaly-event-type
                  nil
                  (or (:anomaly/message anomaly-data)
                      (messages/t :consumer/event-anomaly {:file file-name})))]
    (es/publish! stream (assoc envelope
                               :source/file file-name
                               :anomaly anomaly-data))))

(defn- ^{:stratum 1} governed-request-event
  "Build the governed audit event from the server-created intervention.
   The wire event contributes only its valid source identity, already
   captured in `created` by `event->request`. Everything else is
   server-assigned: `es/intervention-requested` stamps a fresh
   envelope, so `:event/id` (snowflake-ordered), `:event/timestamp`
   (the audit clock), `:event/sequence-number`, and `:workflow/id`
   routing all come from the server. Client-claimed identity,
   timestamps, workflow routing, lifecycle state, and sequence are
   ignored — never preferred over the server envelope."
  [stream created]
  (es/intervention-requested stream (intervention-workflow-id created) created))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-cursor
  "Read the processed cursor from `operator-dir`. A missing or
   unreadable cursor yields the empty cursor. Consumption is therefore
   at-least-once: a crash between routing and the end-of-pass cursor
   write re-delivers on the next pass. Downstream is built for that —
   the accumulator upserts by intervention id, and D-3 application
   mechanisms are idempotent (pausing a paused workflow is a no-op)."
  [operator-dir]
  (let [f (cursor-file operator-dir)]
    (if (.exists f)
      (try
        (let [parsed (edn/read-string (slurp f :encoding "UTF-8"))]
          ;; A v1 cursor has no `:pending-interventions`; `merge` over
          ;; `empty-cursor` supplies the empty map, so an upgrade in
          ;; place neither crashes nor loses the processed sets.
          (merge empty-cursor
                 (select-keys parsed [:processed-intervention-ids
                                      :processed-files
                                      :pending-interventions])))
        (catch Exception _e empty-cursor))
      empty-cursor)))

(defn- ^{:stratum 2} write-cursor!
  "Persist `cursor` atomically: temp file + `Files/move` with
   `ATOMIC_MOVE` + `REPLACE_EXISTING` in the same directory (the
   repo-wide atomic-update idiom — see event-stream `archive.clj`
   `atomic-rename!`), so a crash mid-write can never leave a truncated
   manifest. `File.renameTo` is not used: it is platform-dependent and
   commonly refuses to replace an existing target."
  [operator-dir cursor]
  (let [target (cursor-file operator-dir)
        tmp (io/file operator-dir (str cursor-file-name ".tmp"))]
    (io/make-parents target)
    (spit tmp (pr-str cursor) :encoding "UTF-8")
    (with-open [raf (java.io.RandomAccessFile. tmp "rw")]
      (.force (.getChannel raf) true))
    (Files/move (.toPath tmp)
                (.toPath target)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))))

;; ── Single-event routing ───────────────────────────────────────────────────
(defn- ^{:stratum 2} route-intervention!
  "Create → approval-gate → (optional) apply. Returns the intervention
   id on success, or nil when the request was rejected as an anomaly."
  [operator-stream destination apply! event file-name]
  (let [created (intervention/create-intervention (event->request event))]
    (if (anomaly/anomaly? created)
      (do (publish-anomaly! operator-stream file-name created)
          nil)
      (let [governed-event (governed-request-event destination created)
            _ (es/publish! destination governed-event)
            gate (if (contains? auto-approve-request-sources
                                (:intervention/request-source created))
                   (intervention/approve created)
                   (intervention/start-approval created))
            gated (:intervention gate)]
          (if-not (transition-succeeded? gate)
            (do
              (publish-anomaly!
               operator-stream file-name
               (anomaly/anomaly :invalid-input
                                (:message gate)
                                {:error (:error gate)
                                 :intervention/id (:intervention/id created)}))
              nil)
            (do
              (publish-state-changed! destination gated)
              (when (and apply! (= :approved (:intervention/state gated)))
                (apply! destination gated))
              ;; Returning the gated map, not just the id: a request
              ;; parked at `:pending-human` has to be recoverable when
              ;; the operator's decision arrives in a later pass.
              gated))))))

(defn- ^{:stratum 2} route-decision!
  "Apply an operator verdict to a parked intervention.

   `pending` is the consumer's record of what it left at
   `:pending-human`. An unknown id is an anomaly rather than a silent
   skip: the operator clicked approve on something this consumer has no
   memory of parking, and swallowing that would leave them believing a
   write happened.

   Returns `[outcome intervention-id]` where outcome is `:decided` or
   `:anomaly`."
  [operator-stream destination apply! event file-name pending]
  (let [intervention-id (:intervention/id event)
        decision (:intervention/decision event)
        parked (get pending intervention-id)]
    (cond
      (nil? parked)
      (do (publish-anomaly!
           operator-stream file-name
           (anomaly/anomaly :invalid-input
                            (messages/t :consumer/unknown-decision-target
                                        {:id (str intervention-id)})
                            {:intervention/id intervention-id}))
          [:anomaly intervention-id])

      (not (contains? #{:approve :reject} decision))
      (do (publish-anomaly!
           operator-stream file-name
           (anomaly/anomaly :invalid-input
                            (messages/t :consumer/invalid-decision
                                        {:decision (str decision)})
                            {:intervention/id intervention-id}))
          [:anomaly intervention-id])

      :else
      (let [result (if (= :approve decision)
                     (intervention/approve parked)
                     (intervention/reject parked (:intervention/reason event)))
            decided (:intervention result)]
        (if-not (transition-succeeded? result)
          (do (publish-anomaly!
               operator-stream file-name
               (anomaly/anomaly :invalid-input
                                (:message result)
                                {:error (:error result)
                                 :intervention/id intervention-id}))
              [:anomaly intervention-id])
          (do
            (publish-state-changed! destination decided)
            ;; Same rule as the request path: application runs only on
            ;; an approved transition, and never on a rejection.
            (when (and apply! (= :approved (:intervention/state decided)))
              (apply! destination decided))
            [:decided intervention-id]))))))

;------------------------------------------------------------------------------ Layer 3

;; Consumption pass
(defn- ^{:stratum 3} consume-operator-dir!
  [operator-dir stream apply! accept? stream-for]
  (let [cursor (read-cursor operator-dir)
        files (->> (list-event-files operator-dir)
                   (remove #(contains? (:processed-files cursor)
                                       (.getName ^java.io.File %))))
        result
        (reduce
         (fn [acc ^java.io.File f]
           (let [file-name (.getName f)
                 raw (es/read-event-file f)
                 remembered-file (remember-file acc file-name)]
             (cond
               (nil? raw)
               (do (publish-anomaly! stream file-name
                                     (anomaly/anomaly
                                      :invalid-input
                                      (messages/t :consumer/unreadable-event
                                                  {:file file-name})
                                      {:source/file file-name}))
                   (update remembered-file :anomalies inc))

               (= intervention-decision-event-type (:event/type raw))
               (let [event (revive-request-event raw)]
                 ;; The SAME typed identity set the request path
                 ;; demands, not just the intervention id: a
                 ;; `:workflow/id` that survived tag-stripping as a
                 ;; string would fall through `stream-for` to the
                 ;; default stream, and the transition would be
                 ;; published — and applied — against the wrong
                 ;; destination.
                 (if-not (valid-request-identities? event)
                   (do (publish-anomaly!
                        stream file-name
                        (anomaly/anomaly
                         :invalid-input
                         (messages/t :consumer/invalid-identity {:file file-name})
                         {:source/file file-name}))
                       (update remembered-file :anomalies inc))
                   (let [[outcome intervention-id]
                         (route-decision! stream
                                          (or (stream-for event) stream)
                                          apply!
                                          event
                                          file-name
                                          (get-in acc [:cursor :pending-interventions]))]
                     (if (= :decided outcome)
                       (-> remembered-file
                           (forget-parked intervention-id)
                           (update :routed inc))
                       (update remembered-file :anomalies inc)))))

               (not= intervention-requested-event-type (:event/type raw))
               (update remembered-file :skipped inc)

               :else
               ;; Revive typed identity fields before dedup + routing so
               ;; the id compared against the cursor and the map that
               ;; gets republished are UUID/inst-typed, not the strings
               ;; tag-stripping left behind.
               (let [event (revive-request-event raw)]
                 (cond
                   (not (valid-request-identities? event))
                   (do
                     (publish-anomaly!
                      stream file-name
                      (anomaly/anomaly
                       :invalid-input
                       (messages/t :consumer/invalid-identity {:file file-name})
                       {:source/file file-name}))
                     (update remembered-file :anomalies inc))

                   (not (accept? event))
                   acc

                   (contains? (get-in acc [:cursor :processed-intervention-ids])
                              (:intervention/id event))
                   (update remembered-file :skipped inc)

                   :else
                   (let [intervention-id (:intervention/id event)
                         destination (or (stream-for event) stream)
                         routed (route-intervention! stream
                                                     destination
                                                     apply!
                                                     event
                                                     file-name)
                         remembered (remember-intervention-id remembered-file
                                                              intervention-id)]
                     (if routed
                       (-> remembered
                           (remember-parked routed)
                           (update :routed inc))
                       (update remembered :anomalies inc))))))))
         {:routed 0 :skipped 0 :anomalies 0 :cursor cursor}
         files)]
    (when (seq files)
      (write-cursor! operator-dir (:cursor result)))
    (dissoc result :cursor)))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} consume-pass!
  "Run one consumption pass over `{events-dir}/operator/`.

   Options:
   - :events-dir — base events directory (default ~/.miniforge/events)
   - :stream     — the governed event stream to publish into (required)
   - :apply!     — optional `(fn [stream intervention])` invoked for
                   each newly `:approved` intervention (the D-3
                   application layer). Absent → accepted interventions
                   stop at `:approved`.
   - :accept?    — optional ownership predicate. A false result defers
                   the valid request without advancing either cursor,
                   allowing the owning process to consume it.
   - :stream-for — optional `(fn [event] stream-or-nil)` router. A
                   returned stream receives the governed request and
                   lifecycle; nil falls back to :stream.

   The file lock serializes cursor read → effect → cursor write across
   processes. Returns {:routed <n> :skipped <n> :anomalies <n>}.
   Files and accepted intervention ids recorded in the cursor are never
   routed twice across passes and process restarts."
  [{:keys [events-dir stream apply! accept? stream-for]}]
  (let [operator-dir (if events-dir
                       (es/operator-dir events-dir)
                       (es/operator-dir))
        accept-request? (if-some [predicate accept?]
                          predicate
                          accept-every-request?)
        destination-for (or stream-for (constantly nil))]
    (io/make-parents (consumer-lock-file operator-dir))
    (with-open [channel (FileChannel/open
                         (.toPath (consumer-lock-file operator-dir))
                         (into-array java.nio.file.OpenOption
                                     [java.nio.file.StandardOpenOption/CREATE
                                      java.nio.file.StandardOpenOption/WRITE]))]
      (try
        ;; The lock is released by CLOSING THE CHANNEL (the enclosing
        ;; `with-open`), never by calling `.close`/`.release` on the
        ;; FileLock itself. java.nio guarantees channel closure drops
        ;; every lock the JVM holds on that file, so this is equivalent
        ;; — and it is the only form that works under Babashka, whose
        ;; interpreter refuses both `FileLockImpl.close` and
        ;; `.release` ("Method close on class sun.nio.ch.FileLockImpl
        ;; not allowed!"). That matters because this consumer runs
        ;; inside the bb-hosted workflow runner: a `with-open` on the
        ;; lock threw on EVERY poll tick, and the poller's per-tick
        ;; containment turned it into a warning loop that consumed
        ;; nothing — the control path looked alive and was dead.
        ;; `.tryLock` itself is permitted under bb; only the release
        ;; methods are blocked. See snowflake.clj for the sibling
        ;; JVM-only-lease case.
        ;;
        ;; Bind the lock and hold the binding for the whole pass: it makes
        ;; the acquire-for-the-duration intent explicit and keeps the
        ;; object referenced until the channel closes. `.tryLock` returns
        ;; nil when another holder already owns the lock (the same-JVM
        ;; overlapping case throws instead, caught below).
        (if-let [_lock (.tryLock channel)]
          (consume-operator-dir! operator-dir
                                 stream
                                 apply!
                                 accept-request?
                                 destination-for)
          empty-pass-result)
        (catch Exception e
          (if (overlapping-file-lock? e)
            empty-pass-result
            (throw e)))))))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} start!
  "Start a background poller running [[consume-pass!]] on a fixed
   delay. Options are those of [[consume-pass!]] plus :interval-ms
   (default 1000). Returns a handle for [[stop!]].

   A pass that throws is contained: the error is reported on stderr
   (matching the file-sink's failure convention) and the next tick
   still runs — a transiently unreadable directory must not kill the
   control path for the rest of the process."
  [{:keys [interval-ms] :as opts}]
  (let [executor (Executors/newSingleThreadScheduledExecutor (daemon-thread-factory))
        task (fn []
               (try
                 (consume-pass! opts)
                 (catch Exception e
                   (binding [*out* *err*]
                     (println (str "WARNING: operator-event consumer pass failed: "
                                   (ex-message e)))))))]
    (.scheduleWithFixedDelay ^ScheduledExecutorService executor
                             ^Runnable task
                             initial-poll-delay-ms
                             (long (or interval-ms default-poll-interval-ms))
                             TimeUnit/MILLISECONDS)
    {:executor executor}))
