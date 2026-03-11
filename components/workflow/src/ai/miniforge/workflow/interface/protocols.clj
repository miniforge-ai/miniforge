(ns ai.miniforge.workflow.interface.protocols
  "Workflow protocol and schema-adjacent re-exports."
  (:require
   [ai.miniforge.workflow.interface.protocols.phase-executor :as executor-proto]
   [ai.miniforge.workflow.interface.protocols.workflow :as workflow-proto]
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as observer-proto]
   [ai.miniforge.workflow.protocols.impl.workflow :as workflow-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Workflow workflow-proto/Workflow)
(def PhaseExecutor executor-proto/PhaseExecutor)
(def WorkflowObserver observer-proto/WorkflowObserver)
(def phases workflow-impl/phases)
(def phase-transitions workflow-impl/phase-transitions)
