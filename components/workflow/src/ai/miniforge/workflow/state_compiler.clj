;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-compiler
  "Resolve a validated topology and attach its execution projections."
  (:require [ai.miniforge.fsm.interface :as fsm]
            [ai.miniforge.workflow.actions :as actions]
            [ai.miniforge.workflow.guards :as guards]
            [ai.miniforge.workflow.state-edges :as edges]
            [ai.miniforge.workflow.standard-guards-and-actions]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} phase-projection [entry paused?]
  {:phase (:phase entry) :index (:index entry) :paused? paused?})

(defn- ^{:stratum 0} entry-pairs [entry]
  [[(:state-id entry) entry] [(:paused-state-id entry) entry]])

(defn- ^{:stratum 0} resolved-states [states]
  (-> states guards/resolve-guard-keywords actions/resolve-action-keywords))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} projection-pairs [entry]
  [[(:state-id entry) (phase-projection entry false)]
   [(:paused-state-id entry) (phase-projection entry true)]])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compile-topology [{:keys [states phase-entries]}]
  (let [compiled-states (resolved-states states)
        graph (edges/build-state-graph compiled-states)
        phases (into {} (mapcat projection-pairs) phase-entries)
        entries (into {} (mapcat entry-pairs) phase-entries)
        machine (fsm/define-machine {:fsm/id :workflow-execution
                                     :fsm/initial :pending
                                     :fsm/context {:redirect-count 0}
                                     :fsm/states compiled-states})]
    (assoc machine
           :workflow/phase-entries phase-entries
           :workflow/state->phase phases
           :workflow/state->entry entries
           :workflow/state-graph graph
           :workflow/state-ids (set (keys compiled-states))
           :workflow/source-states states
           :workflow/initial-state :pending)))

(comment ::resolved-workflow-machine)
