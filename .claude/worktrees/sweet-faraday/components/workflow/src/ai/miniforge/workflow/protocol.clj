(ns ai.miniforge.workflow.protocol
  "Workflow protocols for SDLC phase execution.

   Note: The protocols have been moved to:
   - ai.miniforge.workflow.interface.protocols.workflow (Workflow protocol)
   - ai.miniforge.workflow.interface.protocols.phase-executor (PhaseExecutor protocol)
   - ai.miniforge.workflow.interface.protocols.workflow-observer (WorkflowObserver protocol)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.workflow.interface.protocols.workflow :as workflow-proto]
   [ai.miniforge.workflow.interface.protocols.phase-executor :as executor-proto]
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as observer-proto]
   [ai.miniforge.workflow.protocols.impl.workflow :as impl]))

;------------------------------------------------------------------------------ Layer 0
;; Phase definitions (re-export from impl)

(def phases impl/phases)
(def phase-transitions impl/phase-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Protocol re-exports

(def Workflow workflow-proto/Workflow)
(def start workflow-proto/start)
(def get-state workflow-proto/get-state)
(def advance workflow-proto/advance)
(def rollback workflow-proto/rollback)
(def complete workflow-proto/complete)
(def fail workflow-proto/fail)

(def PhaseExecutor executor-proto/PhaseExecutor)
(def execute-phase executor-proto/execute-phase)
(def can-execute? executor-proto/can-execute?)
(def get-phase-requirements executor-proto/get-phase-requirements)

(def WorkflowObserver observer-proto/WorkflowObserver)
(def on-phase-start observer-proto/on-phase-start)
(def on-phase-complete observer-proto/on-phase-complete)
(def on-phase-error observer-proto/on-phase-error)
(def on-workflow-complete observer-proto/on-workflow-complete)
(def on-rollback observer-proto/on-rollback)
