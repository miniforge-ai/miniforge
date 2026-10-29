;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.fsm
  "Compatibility surface for workflow machines, projections and validation."
  (:require [ai.miniforge.fsm.interface :as fsm]
            [ai.miniforge.workflow.state-compiler :as compiler]
            [ai.miniforge.workflow.state-identity :as state-identity]
            [ai.miniforge.workflow.state-legacy :as legacy]
            [ai.miniforge.workflow.state-projection :as projection]
            [ai.miniforge.workflow.state-reachability :as reachability]
            [ai.miniforge.workflow.state-topology :as topology]
            [ai.miniforge.workflow.state-validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-state-prefix state-identity/phase-state-prefix)

(def ^{:stratum 0} paused-phase-state-prefix state-identity/paused-phase-state-prefix)

(def ^{:stratum 0} phase-state-id state-identity/phase-state-id)

(def ^{:stratum 0} paused-phase-state-id state-identity/paused-phase-state-id)

(def ^{:stratum 0} workflow-machine-config legacy/workflow-machine-config)

(def ^{:stratum 0} workflow-machine legacy/workflow-machine)

(def ^{:stratum 0} workflow-states legacy/workflow-states)

(def ^{:stratum 0} workflow-transitions legacy/workflow-transitions)

(def ^{:stratum 0} terminal-states legacy/terminal-states)

(def ^{:stratum 0} succeeded? legacy/succeeded?)

(def ^{:stratum 0} valid-transition? legacy/valid-transition?)

(def ^{:stratum 0} terminal-state? legacy/terminal-state?)

(def ^{:stratum 0} next-state legacy/next-state)

(def ^{:stratum 0} valid-state? legacy/valid-state?)

(def ^{:stratum 0} get-available-events legacy/get-available-events)

(def ^{:stratum 0} fsm-graph legacy/fsm-graph)

(def ^{:stratum 0} transition legacy/transition)

(def ^{:stratum 0} get-machine legacy/get-machine)

(def ^{:stratum 0} initialize legacy/initialize)

(def ^{:stratum 0} transition-fsm legacy/transition-fsm)

(def ^{:stratum 0} is-final? legacy/is-final?)

(def ^{:stratum 0} current-state fsm/current-state)

(def ^{:stratum 0} machine-phase-entry projection/machine-phase-entry)

(def ^{:stratum 0} machine-active-phase-entry projection/machine-active-phase-entry)

(def ^{:stratum 0} execution-status projection/execution-status)

(def ^{:stratum 0} current-phase-id projection/current-phase-id)

(def ^{:stratum 0} current-phase-index projection/current-phase-index)

(def ^{:stratum 0} execution-projection projection/execution-projection)

(def ^{:stratum 0} state-graph reachability/state-graph)

(def ^{:stratum 0} compiled-state-ids reachability/compiled-state-ids)

(def ^{:stratum 0} reachable-states reachability/reachable-states)

(def ^{:stratum 0} unreachable-states reachability/unreachable-states)

(defn ^{:stratum 0} transition-execution
  "Apply an event to a compiled execution machine snapshot.

   Pure delegation to the underlying FSM. Phase 4b removed the parallel
   `redirect-event?` namespace check that bumped `:redirect-count` for
   channel-A redirects — the FSM action `:redirect/inc-count` on the
   guarded `:phase/fail` array now handles every budget increment."
  [machine state event]
  (fsm/transition machine state event))

(defn ^{:stratum 0} transition-execution-result
  "Apply an event to a compiled execution machine snapshot, returning the new
   state or a canonical FSM anomaly for state-local unknown events."
  [machine state event]
  (fsm/transition-result machine state event))

(defn ^{:stratum 0} initialize-execution
  "Initialize a compiled execution machine."
  [machine]
  (fsm/initialize machine))

(defn ^{:stratum 0} start-execution
  "Start a compiled execution machine from :pending."
  [machine state]
  (fsm/transition machine state :start))

;; Keep the raw-builder seam so validation tests can inject malformed topology.
(def ^{:stratum 0} ^:private build-raw-states topology/build-raw-states)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compile-execution-machine [workflow]
  (compiler/compile-topology (build-raw-states workflow)))

(defn ^{:stratum 1} validate-execution-machine [workflow]
  (validation/validate workflow build-raw-states))

(comment
  (transition :pending :start)
  (validate-execution-machine {:workflow/pipeline [{:phase :plan}]}))
