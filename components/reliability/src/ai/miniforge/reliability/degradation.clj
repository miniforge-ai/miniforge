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
   [ai.miniforge.reliability.budget :as budget]
   [ai.miniforge.reliability.messages :as messages]
   [ai.miniforge.event-stream.interface.stream :as stream]
   [ai.miniforge.event-stream.interface.events :as events]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Config

(def ^:private defaults
  (-> (io/resource "config/reliability/defaults.edn") slurp edn/read-string))

(def default-config
  "Default degradation policy config."
  (:degradation-policy defaults))

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
                      :dependency-degraded :degraded
                      :dependency-unavailable :safe-mode
                      :dependency-operator-action :safe-mode
                      :emergency-stop :safe-mode
                      :unknown-failures :safe-mode}}
     :degraded  {:on {:budget-exhausted :safe-mode
                      :dependency-unavailable :safe-mode
                      :dependency-operator-action :safe-mode
                      :emergency-stop :safe-mode
                      :unknown-failures :safe-mode
                      :budget-recovered :nominal}}
     :safe-mode {:on {:operator-exit :nominal}}}}))

;------------------------------------------------------------------------------ Layer 1
;; DegradationManager

(defrecord DegradationManager
  [fsm-state      ; atom wrapping FSM state
   event-stream   ; event stream atom for emitting events
   config])       ; data-driven degradation policy

(def ^:private mode-rank
  {:nominal 0
   :degraded 1
   :safe-mode 2})

(defn- merge-manager-config
  [config]
  (merge default-config config))

(defn create-manager
  "Create a DegradationManager.

   Arguments:
     event-stream - event stream atom
     config       - optional degradation policy overrides"
  [event-stream & [config]]
  (->DegradationManager
   (atom (fsm/initialize degradation-machine))
   event-stream
   (merge-manager-config config)))

(defn current-mode
  "Get the current degradation mode (:nominal, :degraded, or :safe-mode)."
  [manager]
  (fsm/current-state @(:fsm-state manager)))

(defn- transition-signal
  [mode event message & [opts]]
  (cond-> {:mode mode
           :event event
           :message message}
    opts (merge opts)))

(defn- dependency-id-label
  [dependency]
  (some-> dependency :dependency/id name))

(defn- dependency-labels
  [dependencies]
  (->> dependencies
       (keep dependency-id-label)
       sort
       vec))

(defn- dependency-list
  [dependencies]
  (str/join ", " (dependency-labels dependencies)))

(defn- active-dependencies
  [dependency-health]
  (->> dependency-health
       vals
       (remove #(= :healthy (:dependency/status %)))
       vec))

(defn- dependencies-with-status
  [dependencies status]
  (filterv #(= status (:dependency/status %)) dependencies))

(defn- prioritized-status
  [dependencies {:keys [dependency-status-precedence]}]
  (some (fn [status]
          (when (seq (dependencies-with-status dependencies status))
            status))
        dependency-status-precedence))

(defn- dependency-mode
  [status {:keys [dependency-status->mode]}]
  (get dependency-status->mode status))

(defn- dependency-event
  [status {:keys [dependency-status->event]}]
  (get dependency-status->event status))

(defn- dependency-message
  [status dependencies]
  (let [dependency-listing (dependency-list dependencies)
        params {:dependencies dependency-listing}]
    (case status
      :operator-action-required (messages/t :degradation/dependency-operator-action params)
      :misconfigured (messages/t :degradation/dependency-operator-action params)
      :unavailable (messages/t :degradation/dependency-unavailable params)
      :degraded (messages/t :degradation/dependency-degraded params)
      (messages/t :degradation/dependency-degraded params))))

(defn- dependency-signal
  [dependency-health config]
  (let [dependencies (active-dependencies dependency-health)
        status (prioritized-status dependencies config)
        mode (dependency-mode status config)
        event (dependency-event status config)]
    (when (and status mode event)
      (transition-signal mode
                         event
                         (dependency-message status dependencies)
                         {:dependency/status status
                          :dependency/ids (dependency-labels dependencies)
                          :safe-mode-trigger (when (= mode :safe-mode) event)
                          :safe-mode-details (when (= mode :safe-mode)
                                               (dependency-message status dependencies))}))))

(defn- budget-signal
  [budget-state]
  (let [any-critical-exhausted? (budget/critical-budget-exhausted? budget-state)
        any-critical-low? (budget/critical-budget-low? budget-state)]
    (cond
      any-critical-exhausted?
      (transition-signal :safe-mode
                         :emergency-stop
                         "Critical error budget exhausted"
                         {:safe-mode-trigger :error-budget
                          :safe-mode-details "Critical error budget exhausted"})

      any-critical-low?
      (transition-signal :degraded
                         :budget-critical
                         "Critical error budget below 25%"))))

(defn- stronger-signal
  [left right]
  (cond
    (nil? left) right
    (nil? right) left
    (> (mode-rank (:mode right))
       (mode-rank (:mode left))) right
    (= (mode-rank (:mode right))
       (mode-rank (:mode left))) right
    :else left))

(defn recommendation
  "Pure degradation recommendation from budgets and dependency health.

   Returns a signal map with:
   - :mode    target mode
   - :event   FSM transition event
   - :message localized degradation trigger description"
  ([budget-state]
   (recommendation budget-state {} default-config))
  ([budget-state dependency-health]
   (recommendation budget-state dependency-health default-config))
  ([budget-state dependency-health config]
   (let [budget-derived-signal (budget-signal budget-state)
         dependency-derived-signal (dependency-signal dependency-health config)]
     (or (stronger-signal budget-derived-signal dependency-derived-signal)
         (transition-signal :nominal nil (messages/t :degradation/dependency-recovered))))))

(defn- transition!
  "Attempt a state transition, emit events if it succeeds.
   Returns the new mode, or the unchanged mode if transition was invalid."
  [manager {:keys [event message safe-mode-trigger safe-mode-details]}]
  (let [old-state @(:fsm-state manager)
        old-mode (fsm/current-state old-state)
        new-state (fsm/transition degradation-machine old-state event)
        new-mode (fsm/current-state new-state)]
    (when (not= old-mode new-mode)
      (reset! (:fsm-state manager) new-state)
      (when-let [stream (:event-stream manager)]
        (stream/publish! stream
                         (events/degradation-mode-changed stream
                                                         old-mode new-mode message))
        (when (and (= new-mode :safe-mode) safe-mode-trigger)
          (stream/publish! stream
                           (events/safe-mode-entered stream
                                                     safe-mode-trigger
                                                     safe-mode-details)))))
    new-mode))

(defn evaluate-and-transition!
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
         config (:config manager)
         signal (recommendation budget-state dependency-health config)
         target-mode (:mode signal)]
    (case current
      :nominal
      (cond
        (= target-mode :safe-mode)
        (transition! manager signal)

        (= target-mode :degraded)
        (transition! manager signal)

        :else current)

      :degraded
      (cond
        (= target-mode :safe-mode)
        ;; From :degraded, the FSM has a dedicated `:budget-exhausted`
        ;; transition to :safe-mode. The budget signal upstream emits
        ;; `:emergency-stop` (which also works), but `:budget-exhausted`
        ;; is more specific — it lets downstream auditing distinguish a
        ;; budget exhaustion from a true emergency-stop trigger.
        (transition! manager
                     (if (and (= (:event signal) :emergency-stop)
                              (budget/critical-budget-exhausted? budget-state))
                       (assoc signal :event :budget-exhausted)
                       signal))

        (= target-mode :nominal)
        ;; `:degraded → :nominal` always rides the FSM's `:budget-recovered`
        ;; event, but the recovery itself can be budget-driven or
        ;; dependency-driven. Pick the message key from the inputs:
        ;; non-empty budget-state means budgets are being evaluated;
        ;; otherwise the recovery is the dependency clearing.
        (transition! manager
                     (transition-signal
                      :nominal
                      :budget-recovered
                      (messages/t (if (seq budget-state)
                                    :degradation/budget-recovered
                                    :degradation/dependency-recovered))))

        :else current)

      :safe-mode
      current))))

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
                       :dependency-unavailable :dependency-unavailable
                       :dependency-operator-action :dependency-operator-action
                       :emergency-stop)]
        (transition! manager
                     (transition-signal :safe-mode
                                        event-kw
                                        (or details (name trigger))
                                        {:safe-mode-trigger trigger
                                         :safe-mode-details details})))))
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
      (let [new-mode (transition! manager
                                  (transition-signal :nominal
                                                     :operator-exit
                                                     (str principal ": " justification)))]
        (when-let [stream (:event-stream manager)]
          (stream/publish! stream
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
