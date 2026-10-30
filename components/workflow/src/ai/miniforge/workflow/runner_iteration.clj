;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-iteration
  "Apply dashboard control and supervision around one checkpointed phase step."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.context :as ctx]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.workflow.runner-checkpoint :as runner-checkpoint]
            [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [ai.miniforge.workflow.runner-phase-step :as phase-step]
            [ai.miniforge.workflow.stop-signal :as stop-signal]))

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

(defn- ^{:stratum 0} execute-supervised
  [pipeline context callbacks iteration]
  (let [state (monitoring/build-supervision-state context iteration)
        result (monitoring/check-workflow-supervision
                 (:execution/supervision-runtime context) state)]
    (if (= :halt (:status result))
      (monitoring/handle-supervision-halt context result ctx/transition-to-failed)
      (phase-step/execute-phase pipeline context callbacks))))

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
  (let [result (if-let [stop-anomaly (check-stopped control-state)]
                 (apply-dashboard-stop context stop-anomaly)
                 ;; A cancel during the phase interrupts it (and the agent
                 ;; CLI it waits on); the stop is acted on at the next
                 ;; boundary.
                 (stop-signal/interrupting-on-stop
                  control-state
                  #(execute-supervised pipeline context callbacks iteration)))]
    ;; A failed write is warned about and published, never thrown, so
    ;; the run goes on without that checkpoint.
    (runner-checkpoint/checkpoint! result)
    result))

(comment ::supervised-iteration)
