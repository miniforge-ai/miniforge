(ns ai.miniforge.workflow.interface
  "Canonical public boundary for the workflow component.
   Public API groups live under ai.miniforge.workflow.interface.* namespaces,
   while this namespace remains the Polylith entrypoint."
  (:require
   [ai.miniforge.workflow.interface.configurable :as configurable]
   [ai.miniforge.workflow.interface.pipeline :as pipeline]
   [ai.miniforge.workflow.interface.protocols :as protocols]
   [ai.miniforge.workflow.interface.registry :as registry]
   [ai.miniforge.workflow.interface.runtime :as runtime]))

;------------------------------------------------------------------------------ Layer 0
;; Protocols and workflow topology

(def Workflow protocols/Workflow)
(def PhaseExecutor protocols/PhaseExecutor)
(def WorkflowObserver protocols/WorkflowObserver)
(def phases protocols/phases)
(def phase-transitions protocols/phase-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Workflow lifecycle and execution

(def create-workflow runtime/create-workflow)
(def add-observer runtime/add-observer)
(def remove-observer runtime/remove-observer)
(def start runtime/start)
(def get-state runtime/get-state)
(def advance runtime/advance)
(def rollback runtime/rollback)
(def complete runtime/complete)
(def fail runtime/fail)
(def create-phase-executor runtime/create-phase-executor)
(def execute-phase runtime/execute-phase)
(def can-execute? runtime/can-execute?)
(def get-phase-requirements runtime/get-phase-requirements)
(def run-workflow runtime/run-workflow)

;------------------------------------------------------------------------------ Layer 2
;; Pipeline, configurable workflows, persistence, and registry helpers

(def run-pipeline pipeline/run-pipeline)
(def build-pipeline pipeline/build-pipeline)
(def validate-pipeline pipeline/validate-pipeline)
(def run-chain pipeline/run-chain)
(def load-chain pipeline/load-chain)
(def list-chains pipeline/list-chains)

(def load-workflow configurable/load-workflow)
(def execute-workflow configurable/execute-workflow)
(def get-active-workflow configurable/get-active-workflow)
(def set-active-workflow configurable/set-active-workflow)
(def append-event configurable/append-event)
(def load-event-log configurable/load-event-log)
(def replay-workflow configurable/replay-workflow)
(def verify-determinism configurable/verify-determinism)
(def save-workflow configurable/save-workflow)
(def list-workflows configurable/list-workflows)
(def compare-workflows configurable/compare-workflows)
(def create-merge-trigger configurable/create-merge-trigger)
(def stop-trigger! configurable/stop-trigger!)

(def register-workflow! registry/register-workflow!)
(def get-workflow registry/get-workflow)
(def list-workflow-ids registry/list-workflow-ids)
(def workflow-exists? registry/workflow-exists?)
(def workflow-characteristics registry/workflow-characteristics)
(def ensure-initialized! registry/ensure-initialized!)
(def valid-recommendation? registry/valid-recommendation?)
(def explain-recommendation registry/explain-recommendation)
