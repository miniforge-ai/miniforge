;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.heuristic.lifecycle
  "Heuristic status lifecycle per learning.spec §4.

   Status progression: :draft → :shadow → :canary → :active → :deprecated

   Layer 0: Lifecycle transitions (pure)")

;------------------------------------------------------------------------------ Layer 0
;; Valid transitions

(def ^:private valid-transitions
  "Valid status transitions for heuristic lifecycle."
  {:draft      #{:shadow :deprecated}
   :shadow     #{:canary :deprecated}
   :canary     #{:active :deprecated}
   :active     #{:deprecated}
   :deprecated #{}})

(def statuses
  "All valid heuristic statuses."
  #{:draft :shadow :canary :active :deprecated})

(defn valid-transition?
  "Check if a transition from one status to another is valid."
  [from to]
  (contains? (get valid-transitions from #{}) to))

(defn transition
  "Attempt a status transition. Returns new status or nil if invalid."
  [current-status target-status]
  (when (valid-transition? current-status target-status)
    target-status))

(defn can-serve-traffic?
  "Returns true if this status serves production traffic."
  [status]
  (contains? #{:canary :active} status))

(defn promotable?
  "Returns true if this status can be promoted further."
  [status]
  (contains? #{:draft :shadow :canary} status))
