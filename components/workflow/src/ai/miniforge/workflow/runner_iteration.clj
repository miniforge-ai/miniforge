;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-iteration
  "Apply dashboard control and supervision around one checkpointed phase step."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.llm.interface :as llm]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.workflow.runner-checkpoint :as runner-checkpoint]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.runner-phase-step :as phase-step]))

;------------------------------------------------------------------------------ Layer 0

(defonce ^{:stratum 0} ^:private runner-logger
  (log/create-logger {:min-level :debug :output :human}))

(defn- ^{:stratum 0} apply-dashboard-stop
  "Mark context failed after a dashboard stop request."
  [context stop-anomaly]
  (-> context
      (update :execution/errors conj
              (outcome/make-execution-error :dashboard-stop
                                    (:anomaly/message stop-anomaly)
                                    {:data (:anomaly/data stop-anomaly)}))
      (ctx/transition-to-failed)))

(defn- ^{:stratum 0} stop-recorded?
  "True when `context` already records a dashboard stop, as a DAG phase
   does for a cancel between batches."
  [context]
  (boolean (some #(= :dashboard-stop (:type %)) (:execution/errors context))))

(defn- ^{:stratum 0} execute-supervised
  "Run one supervised phase step. Its LLM calls see the run's cancel: once
   CONTROL-STATE is stopped, a streaming agent call in flight kills the
   agent's process tree and returns, and a later one ends as it starts. The
   phase then finishes its own flow, boundary persist included, and the
   caller records the stop."
  [pipeline context callbacks iteration control-state]
  (let [state (monitoring/build-supervision-state context iteration)
        result (monitoring/check-workflow-supervision
                 (:execution/supervision-runtime context) state)]
    (if (= :halt (:status result))
      (monitoring/handle-supervision-halt context result ctx/transition-to-failed)
      (llm/call-with-cancel-signal #(boolean (:stopped @control-state))
                                   #(phase-step/execute-phase pipeline context callbacks
                                                              control-state)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} await-resume!
  "Block while the control state is paused and not stopped. A cancel sets
   :stopped without clearing :paused, so :stopped is re-read on every poll:
   a run paused and then cancelled leaves the wait and takes the stop path
   instead of waiting for a resume that may never come."
  [control-state]
  (when (:paused @control-state)
    (log/debug runner-logger :system :workflow/execution-paused
               {:message (messages/t :status/paused)})
    (while (let [{:keys [paused stopped]} @control-state]
             (and paused (not stopped)))
      (Thread/sleep (defaults/pause-poll-interval-ms)))))

(defn- ^{:stratum 1} check-stopped
  "Return a dashboard-stop anomaly when the control state is stopped."
  [control-state]
  (when (:stopped @control-state)
    (log/warn runner-logger :system :workflow/execution-stopped
              {:message (messages/t :status/stopped-dashboard)
               :data {:reason :dashboard-stop}})
    (anomaly/sub-anomaly :conflict
                         :anomalies.dashboard/stop
                         (messages/t :status/stopped-control)
                         {:reason :dashboard-stop})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-single-iteration
  "Apply control, supervision, phase policy, and checkpoint the resulting context."
  [pipeline context callbacks iteration control-state]
  (await-resume! control-state)
  (let [stopped-before (check-stopped control-state)
        phase-context (if stopped-before
                        context
                        (execute-supervised pipeline context callbacks iteration control-state))
        ;; A stop during the phase is recorded once, unless the phase completed the run.
        stop-anomaly (or stopped-before
                         (when-not (or (= :completed (:execution/status phase-context))
                                       (stop-recorded? phase-context))
                           (check-stopped control-state)))
        result (if stop-anomaly
                 (apply-dashboard-stop phase-context stop-anomaly)
                 phase-context)]
    ;; A failed write is warned about and published, never thrown, so
    ;; the run goes on without that checkpoint.
    (runner-checkpoint/checkpoint! result)
    result))

(comment ::supervised-iteration)
