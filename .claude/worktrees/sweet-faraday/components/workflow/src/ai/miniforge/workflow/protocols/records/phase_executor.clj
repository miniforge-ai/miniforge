(ns ai.miniforge.workflow.protocols.records.phase-executor
  "Record implementations for PhaseExecutor protocol."
  (:require
   [ai.miniforge.workflow.interface.protocols.phase-executor :as p]
   [ai.miniforge.workflow.protocols.impl.phase-executor :as impl]))

(defrecord PlanPhaseExecutor [llm-backend]
  p/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (impl/execute-plan-phase llm-backend workflow-state context))

  (can-execute? [_this phase]
    (= phase :plan))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:spec]
     :optional-artifacts []}))

(defrecord ImplementPhaseExecutor [llm-backend]
  p/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (impl/execute-implement-phase llm-backend workflow-state context))

  (can-execute? [_this phase]
    (= phase :implement))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:plan]
     :optional-artifacts [:design]}))

(defrecord VerifyPhaseExecutor [llm-backend]
  p/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (impl/execute-verify-phase llm-backend workflow-state context))

  (can-execute? [_this phase]
    (= phase :verify))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:code]
     :optional-artifacts []}))

(defn create-plan-executor
  "Create a PlanPhaseExecutor instance."
  [llm-backend]
  (->PlanPhaseExecutor llm-backend))

(defn create-implement-executor
  "Create an ImplementPhaseExecutor instance."
  [llm-backend]
  (->ImplementPhaseExecutor llm-backend))

(defn create-verify-executor
  "Create a VerifyPhaseExecutor instance."
  [llm-backend]
  (->VerifyPhaseExecutor llm-backend))
