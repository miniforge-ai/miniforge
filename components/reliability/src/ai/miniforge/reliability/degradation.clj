;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

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
   [ai.miniforge.event-stream.core :as events]))

;------------------------------------------------------------------------------ Layer 0
;; FSM definition

(def degradation-machine
  "Degradation mode state machine.

   Transitions:
     :nominal   → :degraded   on :budget-critical
     :nominal   → :safe-mode  on :emergency-stop
     :degraded  → :safe-mode  on :budget-exhausted or :emergency-stop
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
                      :emergency-stop :safe-mode
                      :unknown-failures :safe-mode}}
     :degraded  {:on {:budget-exhausted :safe-mode
                      :emergency-stop :safe-mode
                      :unknown-failures :safe-mode
                      :budget-recovered :nominal}}
     :safe-mode {:on {:operator-exit :nominal}}}}))

;------------------------------------------------------------------------------ Layer 1
;; DegradationManager

(defrecord DegradationManager
  [fsm-state      ; atom wrapping FSM state
   event-stream   ; event stream atom for emitting events
   config])       ; {:unknown-failure-threshold 3}

(defn create-manager
  "Create a DegradationManager.

   Arguments:
     event-stream - event stream atom
     config       - optional {:unknown-failure-threshold 3}"
  [event-stream & [config]]
  (->DegradationManager
   (atom (fsm/initialize degradation-machine))
   event-stream
   (merge {:unknown-failure-threshold 3} config)))

(defn current-mode
  "Get the current degradation mode (:nominal, :degraded, or :safe-mode)."
  [manager]
  (fsm/current-state @(:fsm-state manager)))

(defn- transition!
  "Attempt a state transition, emit events if it succeeds.
   Returns the new mode, or the unchanged mode if transition was invalid."
  [manager event trigger-str]
  (let [old-state @(:fsm-state manager)
        old-mode (fsm/current-state old-state)
        new-state (fsm/transition degradation-machine old-state event)
        new-mode (fsm/current-state new-state)]
    (when (not= old-mode new-mode)
      (reset! (:fsm-state manager) new-state)
      (when-let [stream (:event-stream manager)]
        (events/publish! stream
                         (events/degradation-mode-changed stream
                                                         old-mode new-mode trigger-str))))
    new-mode))

(defn evaluate-and-transition!
  "Evaluate budget state and trigger mode transition if warranted.

   Arguments:
     manager      - DegradationManager
     budget-state - map of budgets from engine/compute-cycle!
                    {[sli-name tier window] -> budget-map}

   Returns: current degradation mode."
  [manager budget-state]
  (let [current (current-mode manager)
        any-critical-exhausted? (some (fn [[_ b]]
                                        (and (= :critical (:error-budget/tier b))
                                             (<= (:error-budget/remaining b) 0.0)))
                                      budget-state)
        any-critical-low? (some (fn [[_ b]]
                                  (and (= :critical (:error-budget/tier b))
                                       (< (:error-budget/remaining b) 0.25)))
                                budget-state)]
    (case current
      :nominal
      (cond
        any-critical-exhausted?
        (transition! manager :emergency-stop "Critical error budget exhausted")

        any-critical-low?
        (transition! manager :budget-critical "Critical error budget below 25%")

        :else current)

      :degraded
      (cond
        any-critical-exhausted?
        (transition! manager :budget-exhausted "Critical error budget exhausted in degraded mode")

        (not any-critical-low?)
        (transition! manager :budget-recovered "Error budgets recovered above threshold")

        :else current)

      :safe-mode
      current)))

(defn enter-safe-mode!
  "Force entry to safe-mode. Works from any state.

   Arguments:
     manager - DegradationManager
     trigger - keyword (:error-budget | :emergency-stop | :unknown-failures | :manual)
     details - string with additional context"
  [manager trigger details]
  (let [current (current-mode manager)]
    (when (not= current :safe-mode)
      (let [event-kw (case trigger
                       :emergency-stop :emergency-stop
                       :unknown-failures :unknown-failures
                       :emergency-stop)]
        (transition! manager event-kw (or details (name trigger)))
        ;; Emit safe-mode/entered event
        (when-let [stream (:event-stream manager)]
          (events/publish! stream
                           (events/safe-mode-entered stream trigger details))))))
  (current-mode manager))

(defn exit-safe-mode!
  "Exit safe-mode. Requires explicit justification per N8 §3.4.3.

   Arguments:
     manager       - DegradationManager
     justification - string explaining why safe-mode is being exited
     principal     - string identifying who is exiting safe-mode

   Returns: new mode (should be :nominal) or :safe-mode if not in safe-mode."
  [manager justification principal]
  (let [current (current-mode manager)]
    (when (= current :safe-mode)
      (let [new-mode (transition! manager :operator-exit
                                  (str principal ": " justification))]
        (when-let [stream (:event-stream manager)]
          (events/publish! stream
                           (events/safe-mode-exited stream principal justification 0 0)))
        new-mode))))

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
