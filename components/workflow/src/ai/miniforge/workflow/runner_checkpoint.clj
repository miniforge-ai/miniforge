;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-checkpoint
  "Checkpoint the runner's context and report a failed write without
   stopping the run.

   `checkpoint-store/persist-execution-state!` returns an anomaly when a
   write fails (a full disk, a root the process cannot write). Dropping
   it would leave a run that cannot be resumed and nobody told; failing
   the run for it would throw away a phase that succeeded. This takes the
   middle the checkpoint spec asks for: log a warning, emit
   the registered phase or snapshot failure fact, and carry on."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-event-dispatch :as dispatch]
            [ai.miniforge.workflow.runner-checkpoint-events :as events]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} thrown-message [throw-context]
  (let [thrown (:throwable throw-context)]
    (or (ex-message thrown) (some-> thrown class .getName))))

(defn- ^{:stratum 0} warn-write-failure! [context write-failure phase fields]
  (let [phase-label (some-> phase name)
        message (messages/t :warn/checkpoint-write-failed
                            {:phase phase-label
                             :error (:error/message fields)})]
    (log/warn (:execution/logger context) :system :workflow/checkpoint-write-failed
              {:message message
               :data (:anomaly/data write-failure)})))

(defn- ^{:stratum 0} warn-refused-failure! [context error]
  (let [message (messages/t :warn/publish-event {:error error})]
    (log/warn (:execution/logger context) :system :workflow/checkpoint-write-failed
              {:message message})))

(defn- ^{:stratum 0} warn-announcement! [context written error]
  (let [phase (some-> (:checkpoint/phase written) name)
        message (messages/t :warn/checkpoint-announce-failed {:phase phase
                                                           :error error})]
    (log/warn (:execution/logger context) :system :workflow/checkpoint-written {:message message})))

(defn- ^{:stratum 0} refusal-message [receipt]
  (when (anomaly/any-anomaly? receipt) (:anomaly/message receipt)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} announce-write!
  "Publish the write the store just made. Its paths and paired storage
   identity have already been captured by the store. The run's events
   then name its checkpoint root, which the event cleanup's retention hold
   reads to keep those events while the checkpoint exists, wherever the
   run wrote it. Needs a stateful stream (see `report-write-failure!`).
   The write already succeeded, so a failed or refused announcement is
   logged and never thrown; an Error still propagates."
  [context written]
  (let [stream (get-in context [:execution/opts :event-stream])
        warn! (partial warn-announcement! context written)]
    (when (and (:checkpoint/machine-snapshot-path written) (dispatch/stateful-stream? stream))
      (try+
        (some-> (dispatch/publish-event! stream (events/written stream context written))
                refusal-message
                warn!)
        #_{:clj-kondo/ignore [:unresolved-symbol]}
        (catch dispatch/nonfatal? _
          (warn! (thrown-message &throw-context)))))))

(defn- ^{:stratum 1} report-write-failure!
  "Warn and publish the registered phase or snapshot failure fact. Without
   a phase the authoritative machine state identifies the snapshot failure.
   Missing/observer-only streams warn only; refused and nonfatal failed
   publications warn and return. Fatal errors still propagate."
  [context write-failure]
  (let [stream (get-in context [:execution/opts :event-stream])
        phase (get-in write-failure [:anomaly/data :phase/name])
        fields (events/failure-fields write-failure)
        warn! (partial warn-refused-failure! context)]
    (warn-write-failure! context write-failure phase fields)
    (when (dispatch/stateful-stream? stream)
      (try+
        (some-> (dispatch/publish-event! stream (events/failed stream context phase fields))
                refusal-message
                warn!)
        #_{:clj-kondo/ignore [:unresolved-symbol]}
        (catch dispatch/nonfatal? _
          (warn! (thrown-message &throw-context)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} checkpoint!
  "Persist `context`'s checkpoint and return what the store returned. A
   written phase checkpoint is announced with `:workflow/checkpoint-written`.
   A write failure is reported (warning plus event) and returned as the
   store's anomaly; it is never thrown, so the caller keeps running."
  [context]
  (let [written (checkpoint-store/persist-execution-state! context)]
    (if (anomaly/anomaly? written)
      (report-write-failure! context written)
      (announce-write! context written))
    written))

(defn ^{:stratum 2} report!
  "The second half of `checkpoint!`, for a write made earlier: report
   `written` (the store's result) if it is a failure, else announce it.
   The run's start checkpoint is written before `:workflow/started` and
   reported after it, so started stays the run's first event. Returns
   `written`."
  [context written]
  (if (anomaly/anomaly? written)
    (report-write-failure! context written)
    (announce-write! context written))
  written)

(defn ^{:stratum 2} persist!
  "`checkpoint!` without the announcement, for the terminal checkpoint,
   written once the run's events are closed. A write failure is reported
   and returned, never thrown."
  [context]
  (let [written (checkpoint-store/persist-execution-state! context)]
    (when (anomaly/anomaly? written)
      (report-write-failure! context written))
    written))

(comment
  (checkpoint! {}))
