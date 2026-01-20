(ns ai.miniforge.workflow.interface
  "Public API for the workflow component.
   Handles SDLC phase execution: Plan → Design → Implement → Verify → Review → Release → Observe"
  (:require
   [ai.miniforge.workflow.core :as core]
   [ai.miniforge.workflow.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Workflow proto/Workflow)
(def PhaseExecutor proto/PhaseExecutor)
(def WorkflowObserver proto/WorkflowObserver)

;; Phase definitions
(def phases proto/phases)
(def phase-transitions proto/phase-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Workflow creation

(def create-workflow
  "Create a workflow manager.

   Options:
   - :max-iterations-per-phase - Max inner loop iterations per phase (default 5)
   - :max-rollbacks - Max rollbacks before failure (default 3)
   - :timeout-per-phase-ms - Timeout per phase in ms (default 10 minutes)

   Example:
     (create-workflow)
     (create-workflow {:max-rollbacks 5})"
  core/create-workflow)

(def add-observer
  "Add a workflow observer for meta-loop integration.
   Observers receive callbacks on phase transitions and completions."
  core/add-observer)

(def remove-observer
  "Remove a workflow observer."
  core/remove-observer)

;------------------------------------------------------------------------------ Layer 2
;; Workflow operations

(defn start
  "Start a new workflow from a specification.

   Arguments:
   - workflow - Workflow instance
   - spec - Map with :title and :description
   - context - Execution context

   Returns workflow-id."
  [workflow spec context]
  (proto/start workflow spec context))

(defn get-state
  "Get current workflow state.

   Returns:
     {:workflow/id uuid
      :workflow/phase keyword
      :workflow/status keyword (:pending :running :completed :failed)
      :workflow/artifacts []
      :workflow/errors []
      :workflow/metrics {:tokens :cost-usd :duration-ms}
      :workflow/history []}"
  [workflow workflow-id]
  (proto/get-state workflow workflow-id))

(defn advance
  "Advance workflow based on phase execution result.

   phase-result:
     {:success? bool
      :artifacts []
      :errors []
      :metrics {}}

   Returns updated workflow state."
  [workflow workflow-id phase-result]
  (proto/advance workflow workflow-id phase-result))

(defn rollback
  "Rollback workflow to a previous phase.

   Arguments:
   - workflow - Workflow instance
   - workflow-id - Workflow identifier
   - target-phase - Phase to rollback to
   - reason - Reason for rollback

   Returns updated workflow state."
  [workflow workflow-id target-phase reason]
  (proto/rollback workflow workflow-id target-phase reason))

(defn complete
  "Mark workflow as complete.
   Returns final workflow state."
  [workflow workflow-id]
  (proto/complete workflow workflow-id))

(defn fail
  "Mark workflow as failed.
   Returns final workflow state."
  [workflow workflow-id error]
  (proto/fail workflow workflow-id error))

;------------------------------------------------------------------------------ Layer 3
;; Phase execution

(def create-phase-executor
  "Create a phase executor for a specific phase.

   Arguments:
   - phase - Phase keyword (:plan :implement :verify etc.)
   - llm-backend - LLM backend for agent execution

   Returns PhaseExecutor or nil if phase not supported."
  core/create-phase-executor)

(defn execute-phase
  "Execute a phase using an executor.

   Returns:
     {:success? bool
      :artifacts []
      :errors []
      :metrics {}}"
  [executor workflow-state context]
  (proto/execute-phase executor workflow-state context))

(defn can-execute?
  "Check if an executor can handle a phase."
  [executor phase]
  (proto/can-execute? executor phase))

(defn get-phase-requirements
  "Get required inputs for a phase."
  [executor phase]
  (proto/get-phase-requirements executor phase))

;------------------------------------------------------------------------------ Layer 4
;; Workflow runner

(def run-workflow
  "Run a complete workflow from spec to completion.

   Arguments:
   - workflow - Workflow instance
   - spec - Specification map {:title :description}
   - context - Execution context {:llm-backend :budget :tags}

   Returns final workflow state.

   Example:
     (def wf (create-workflow))
     (run-workflow wf
                   {:title \"Create greeting function\"
                    :description \"A function that says hello\"}
                   {:llm-backend llm-client})"
  core/run-workflow)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.llm.interface :as llm])

  ;; Create workflow
  (def wf (create-workflow))

  ;; Mock LLM
  (def llm-client (llm/mock-client {:output "Mock response"}))

  ;; Start workflow
  (def wf-id (start wf {:title "Test" :description "Test workflow"} {}))

  ;; Get state
  (get-state wf wf-id)

  ;; Run full workflow
  (run-workflow wf
                {:title "Create greeting function"
                 :description "A function that says hello"}
                {:llm-backend llm-client})

  :end)
