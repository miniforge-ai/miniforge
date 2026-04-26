;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.heuristic.lifecycle
  "Heuristic status lifecycle per learning.spec §4.

   Status progression: :draft → :shadow → :canary → :active → :deprecated

   Layer 0: Lifecycle transitions (pure)"
  (:require
   [ai.miniforge.heuristic.lifecycle-config :as config]
   [ai.miniforge.fsm.interface :as fsm]))

;------------------------------------------------------------------------------ Layer 0
;; Valid transitions

(def statuses
  "All valid heuristic statuses."
  (config/statuses))

(def ^:private lifecycle-machine-config
  "Heuristic lifecycle state machine configuration."
  (config/machine-config))

(def ^:private lifecycle-machine
  "Compiled heuristic lifecycle machine."
  (fsm/define-machine lifecycle-machine-config))

(def ^:private transition-events
  "Map of [from-status to-status] pairs to lifecycle events."
  (config/transition-events))

(defn- transition-event
  "Resolve the FSM event for a lifecycle transition."
  [from-status to-status]
  (get transition-events [from-status to-status]))

(defn valid-transition?
  "Check if a transition from one status to another is valid."
  [from to]
  (some? (transition-event from to)))

(defn transition
  "Attempt a status transition. Returns new status or nil if invalid."
  [current-status target-status]
  (when-let [event (transition-event current-status target-status)]
    (let [state {:_state current-status}
          transitioned (fsm/transition lifecycle-machine state event)]
      (fsm/current-state transitioned))))

(defn can-serve-traffic?
  "Returns true if this status serves production traffic."
  [status]
  (contains? #{:canary :active} status))

(defn promotable?
  "Returns true if this status can be promoted further."
  [status]
  (contains? #{:draft :shadow :canary} status))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (valid-transition? :draft :shadow)      ;; => true
  (valid-transition? :active :draft)      ;; => false
  (transition :draft :shadow)             ;; => :shadow
  (transition :active :draft)             ;; => nil
  (can-serve-traffic? :canary)            ;; => true
  (promotable? :active)                   ;; => false

  :leave-this-here)
