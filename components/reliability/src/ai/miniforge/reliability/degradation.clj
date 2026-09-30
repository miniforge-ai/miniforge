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
(ns ai.miniforge.reliability.degradation
  "Degradation mode FSM per N1 §5.5.5 and N8 §3.4.

   Three modes:
     :nominal   — Full autonomous execution
     :degraded  — Increased validation, reduced concurrency
     :safe-mode — Autonomy demoted to A0, workflows queued

   Layer 0: FSM definition (pure)
   Layer 1: DegradationManager (stateful)"
  (:require
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.reliability.budget :as budget]
   [ai.miniforge.reliability.degradation-config :as config]
   [ai.miniforge.reliability.degradation-signal :as signals]
   [ai.miniforge.reliability.messages :as messages]
   [ai.miniforge.reliability.safe-mode-boundary :as boundary]
   [ai.miniforge.event-stream.interface.stream :as stream]
   [ai.miniforge.event-stream.interface.events :as events]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-config config/defaults)

(def ^{:stratum 0} recommendation signals/recommendation)

;; FSM definition
(def ^{:stratum 0} degradation-machine
  "Degradation mode state machine.

   Transitions:
     :nominal   → :degraded   on :budget-critical
     :nominal   → :safe-mode  on :emergency-stop or :manual
     :degraded  → :safe-mode  on :budget-exhausted, :emergency-stop, or :manual
     :degraded  → :nominal    on :budget-recovered
     :safe-mode → :nominal    on :operator-exit (requires justification)"
  (fsm/define-machine
   {:fsm/id :degradation-mode
    :fsm/initial :nominal
    :fsm/context {:entered-at nil
                  :trigger nil
                  :trigger-details nil
                  :pre-autonomy-levels {}
                  :queued-workflow-ids []}
    :fsm/states
    {:nominal   {:on {:budget-critical :degraded
                      :dependency-degraded :degraded
                      :dependency-unavailable :safe-mode
                      :dependency-operator-action :safe-mode
                      :emergency-stop :safe-mode
                      :manual :safe-mode
                      :unknown-failures :safe-mode}}
     :degraded  {:on {:budget-exhausted :safe-mode
                      :dependency-unavailable :safe-mode
                      :dependency-operator-action :safe-mode
                      :emergency-stop :safe-mode
                      :manual :safe-mode
                      :unknown-failures :safe-mode
                      :budget-recovered :nominal}}
     :safe-mode {:on {:operator-exit :nominal}}}}))

;; DegradationManager
(defrecord ^{:stratum 0} DegradationManager
  [fsm-state      ; atom wrapping FSM state
   event-stream   ; event stream atom for emitting events
   config])  ; data-driven degradation policy

(defn ^{:stratum 0} current-mode
  [manager]
  (fsm/current-state @(:fsm-state manager)))

(defn ^{:stratum 0} stop-result [manager]
  (some-> (:safe-mode/stop-result manager) deref))

(defn- ^{:stratum 0} publish-transition! [manager old-mode new-mode signal]
  (when-let [stream (:event-stream manager)]
    (stream/publish! stream (events/degradation-mode-changed stream old-mode new-mode (:message signal)))
    (when (and (= new-mode :safe-mode) (:safe-mode-trigger signal))
      (stream/publish! stream (events/safe-mode-entered stream (:safe-mode-trigger signal)
                                                       (:safe-mode-details signal))))))

(defn- ^{:stratum 0} applicable-signal [current budgets proposed]
  (case current
    :nominal (when (contains? #{:safe-mode :degraded} (:mode proposed)) proposed)
    :degraded (case (:mode proposed)
                :safe-mode (cond-> proposed
                             (and (= :emergency-stop (:event proposed))
                                  (budget/critical-budget-exhausted? budgets))
                             (assoc :event :budget-exhausted))
                :nominal (config/signal :nominal :budget-recovered
                           (messages/t (if (seq budgets) :degradation/budget-recovered
                                                         :degradation/dependency-recovered)))
                nil)
    nil))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} transition!
  "Attempt a state transition, emit events if it succeeds.
   Returns the new mode, or the unchanged mode if transition was invalid."
  [manager signal]
  (let [state (:fsm-state manager)]
    (locking state
      (let [old-state @state
            old-mode (fsm/current-state old-state)
            event (if (and (= old-mode :nominal) (= :budget-exhausted (:event signal)))
                    :emergency-stop (:event signal))
            unchanged? (or (= old-mode (:mode signal))
                           (and (= :safe-mode old-mode) (not= :operator-exit event)))
            new-state (if unchanged? old-state (fsm/transition degradation-machine old-state event))
            new-mode (fsm/current-state new-state)]
        (when (not= old-mode new-mode)
          (when (= new-mode :safe-mode)
            (reset! (:safe-mode/stop-result manager)
                    (boundary/stop-with-exception-handling (get-in manager [:config :before-safe-mode!]) signal)))
          (reset! state new-state)
          (publish-transition! manager old-mode new-mode signal))
        new-mode))))

(defn ^{:stratum 1} create-manager
  [event-stream & [config]]
  (if-not (and (or (nil? config) (map? config))
               (or (not (contains? config :before-safe-mode!)) (fn? (:before-safe-mode! config))))
    (anomaly/anomaly :invalid-input (messages/t :degradation/invalid-config) {})
    (assoc (->DegradationManager (atom (fsm/initialize degradation-machine))
                                 event-stream (merge default-config config))
           :safe-mode/stop-result (atom nil))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} enter-safe-mode!
  [manager trigger details]
  (let [current (current-mode manager)]
    (when (not= current :safe-mode)
      (let [event-kw (case trigger
                       :emergency-stop :emergency-stop
                       :unknown-failures :unknown-failures
                       :dependency-unavailable :dependency-unavailable
                       :dependency-operator-action :dependency-operator-action
                       :manual :manual
                       :emergency-stop)]
        (transition! manager
                     (config/signal :safe-mode event-kw (or details (name trigger))
                                    {:safe-mode-trigger trigger :safe-mode-details details})))))
  (current-mode manager))

(defn ^{:stratum 2} exit-safe-mode!
  "Exit safe-mode. Requires explicit justification per N8 §3.4.3.

   Arguments:
     manager       - DegradationManager
     justification - string explaining why safe-mode is being exited
     principal     - string identifying who is exiting safe-mode

   Returns: new mode (should be :nominal) or :safe-mode if not in safe-mode."
  [manager justification principal]
  (if-not (and (string? justification) (not (str/blank? justification))
               (string? principal) (not (str/blank? principal)))
    (anomaly/anomaly :invalid-input (messages/t :safe-mode/invalid-exit) {})
    (locking (:fsm-state manager)
      (when (= :safe-mode (current-mode manager))
        (let [message (messages/t :degradation/operator-exit-reason
                                 {:principal principal :justification justification})
              new-mode (transition! manager (config/signal :nominal :operator-exit message))]
          (when-let [stream (:event-stream manager)]
            (stream/publish! stream (events/safe-mode-exited stream principal justification 0 0)))
          new-mode)))))

(defn ^{:stratum 2} evaluate-and-transition!
  "Evaluate budget state and trigger mode transition if warranted.

   Arguments:
     manager      - DegradationManager
     budget-state - map of budgets from engine/compute-cycle!
                    {[sli-name tier window] -> budget-map}
     dependency-health - optional dependency health projection

   Returns: current degradation mode."
  ([manager budget-state]
   (evaluate-and-transition! manager budget-state {}))
  ([manager budget-state dependency-health]
   (let [current (current-mode manager)
         proposed (recommendation budget-state dependency-health (:config manager))
         signal (applicable-signal current budget-state proposed)]
     (if signal (transition! manager signal) current))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def stream (atom {:events [] :subscribers {} :filters {} :sequence-numbers {} :sinks []}))
  (def mgr (create-manager stream))

  (current-mode mgr) ;; => :nominal

  (evaluate-and-transition! mgr
                            {[:SLI-1 :critical :7d]
                             {:error-budget/tier :critical
                              :error-budget/remaining 0.20
                              :error-budget/burn-rate 1.5}})
  ;; => :degraded

  (enter-safe-mode! mgr :emergency-stop "Production incident detected")
  ;; => :safe-mode

  (exit-safe-mode! mgr "Incident resolved, metrics recovered" "chris@miniforge.ai")
  ;; => :nominal

  :leave-this-here)
