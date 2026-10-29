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
   `:workflow/checkpoint-write-failed`, and carry on."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as es]
            [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-event-dispatch :as dispatch]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failure-fields
  "The `:error/message` / `:failure/class` pair the failure event carries,
   classified from the exception alone. The anomaly's own type is left
   out: `:unavailable` would classify every local disk error as an
   external-service failure."
  [write-failure]
  (let [data (:anomaly/data write-failure)
        message (or (:anomaly/ex-message data) (:anomaly/message write-failure))]
    {:error/message message
     :failure/class (failure/classify {:exception/class (:anomaly/ex-class data)
                                       :error/message message})}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} report-write-failure!
  "Warn on the run's logger and publish `:workflow/checkpoint-write-failed`.
   The event names a phase, so a failure with none (a context checkpointed
   before any phase ran) is logged only. So is one with no stateful event
   stream: the constructor dereferences the stream, which throws on nil
   and on a WebSocket transport map. A refused publication is logged too;
   it never fails the run."
  [context write-failure]
  (let [logger (:execution/logger context)
        stream (get-in context [:execution/opts :event-stream])
        phase (get-in write-failure [:anomaly/data :phase/name])
        fields (failure-fields write-failure)]
    (log/warn logger :system :workflow/checkpoint-write-failed
              {:message (messages/t :warn/checkpoint-write-failed
                                    {:phase (some-> phase name)
                                     :error (:error/message fields)})
               :data (:anomaly/data write-failure)})
    (when (and (keyword? phase) (dispatch/stateful-stream? stream))
      (let [receipt (dispatch/publish-event!
                     stream
                     (es/checkpoint-write-failed stream (:execution/id context) phase fields))]
        (when (anomaly/any-anomaly? receipt)
          (log/warn logger :system :workflow/checkpoint-write-failed
                    {:message (messages/t :warn/publish-event
                                          {:error (:anomaly/message receipt)})}))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} checkpoint!
  "Persist `context`'s checkpoint and return what the store returned. A
   write failure is reported (warning plus event) and returned as the
   store's anomaly; it is never thrown, so the caller keeps running."
  [context]
  (let [written (checkpoint-store/persist-execution-state! context)]
    (when (anomaly/anomaly? written)
      (report-write-failure! context written))
    written))

(comment
  (checkpoint! {}))
