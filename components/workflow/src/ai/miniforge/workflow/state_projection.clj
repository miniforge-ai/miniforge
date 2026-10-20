;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-projection
  "Execution fields projected from the authoritative machine snapshot."
  (:require [ai.miniforge.fsm.interface :as fsm]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} machine-phase-entry
  "Get phase metadata for the current compiled machine state."
  [machine state]
  (get-in machine [:workflow/state->phase (fsm/current-state state)]))

(defn ^{:stratum 0} machine-active-phase-entry
  "Get the active workflow phase entry for the current compiled machine state."
  [machine state]
  (get-in machine [:workflow/state->entry (fsm/current-state state)]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execution-status
  "Project coarse execution status from a compiled machine snapshot."
  [machine state]
  (let [state-id (fsm/current-state state)]
    (cond
      (= :pending state-id) :pending
      (= :completed state-id) :completed
      (= :failed state-id) :failed
      (= :cancelled state-id) :cancelled
      (= :paused state-id) :paused
      (:paused? (machine-phase-entry machine state)) :paused
      :else :running)))

(defn ^{:stratum 1} current-phase-id
  "Project the current phase keyword from a compiled machine snapshot."
  [machine state]
  (:phase (machine-phase-entry machine state)))

(defn ^{:stratum 1} current-phase-index
  "Project the current phase index from a compiled machine snapshot."
  [machine state]
  (:index (machine-phase-entry machine state)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execution-projection
  "Project execution fields from a compiled machine snapshot."
  [machine state]
  (let [ctx (fsm/context state)
        status (execution-status machine state)
        phase (current-phase-id machine state)
        index (current-phase-index machine state)]
    {:execution/status status
     :execution/current-phase phase
     :execution/phase-index index
     :execution/redirect-count (get ctx :redirect-count 0)}))

(comment ::workflow-state-language)
