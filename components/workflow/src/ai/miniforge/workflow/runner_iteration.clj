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
            [slingshot.slingshot :refer [try+ throw+]]))

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

(defn- ^{:stratum 0} interrupt-on-cancel!
  "Interrupt the calling thread when CONTROL-STATE is stopped, now or until
   the returned function is called. Interrupting the phase thread is what
   stops an in-flight agent call: the LLM client destroys the agent's
   process tree when its thread is interrupted. The returned function ends
   the watch and returns true when it sent the interrupt, clearing the flag
   it set so the runner's own waits and checkpoint writes are not
   interrupted after the phase. The lock keeps a cancel racing the end of
   the phase from interrupting the thread after the flag is cleared."
  [control-state]
  (let [thread    (Thread/currentThread)
        watch-key (Object.)
        lock      (Object.)
        state     (volatile! :armed)
        fire!     (fn [control]
                    (locking lock
                      (when (and (:stopped control) (= :armed @state))
                        (vreset! state :fired)
                        (.interrupt thread))))]
    (add-watch control-state watch-key (fn [_ _ _ control] (fire! control)))
    (fire! @control-state)
    (fn disarm! []
      (locking lock
        (remove-watch control-state watch-key)
        (let [fired? (= :fired @state)]
          (vreset! state :disarmed)
          (when fired? (Thread/interrupted))
          fired?)))))

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

(defn- ^{:stratum 1} run-until-cancelled
  "Run phase step F, interrupting it if CONTROL-STATE is stopped while it
   runs. Returns {:result r} when F returns, even after an interrupt, and
   {:cancelled? true} when F threw after the cancel interrupted it. Anything
   F throws without a cancel is rethrown unchanged."
  [control-state f]
  (let [disarm! (interrupt-on-cancel! control-state)]
    (try+
      (let [result (f)]
        (disarm!)
        {:result result})
      (catch Object _
        (if (disarm!)
          {:cancelled? true}
          (throw+))))))

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
  (let [stop-anomaly (check-stopped control-state)
        outcome      (when-not stop-anomaly
                       (run-until-cancelled control-state
                                            #(execute-supervised pipeline context callbacks iteration)))
        result       (cond
                       stop-anomaly
                       (apply-dashboard-stop context stop-anomaly)

                       ;; The cancel cut the phase short: stop the run from
                       ;; the context the phase started with.
                       (:cancelled? outcome)
                       (apply-dashboard-stop context (check-stopped control-state))

                       ;; A phase that returned despite the interrupt is kept;
                       ;; the next iteration takes the stop path.
                       :else
                       (:result outcome))]
    ;; A failed write is warned about and published, never thrown, so
    ;; the run goes on without that checkpoint.
    (runner-checkpoint/checkpoint! result)
    result))

(comment ::supervised-iteration)
