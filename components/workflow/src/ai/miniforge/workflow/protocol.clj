(ns ai.miniforge.workflow.protocol
  "Workflow protocols for SDLC phase execution.

   The workflow component handles the Outer Loop:
   Plan → Design → Implement → Verify → Review → Release → Observe

   Each phase produces artifacts that flow to the next phase.")

;------------------------------------------------------------------------------ Layer 0
;; Phase definitions

(def phases
  "Ordered SDLC phases."
  [:spec :plan :design :implement :verify :review :release :observe :done])

(def phase-transitions
  "Valid phase transitions."
  {:spec      #{:plan}
   :plan      #{:design :implement}  ; design is optional
   :design    #{:implement}
   :implement #{:verify}
   :verify    #{:review :implement}  ; can loop back on failure
   :review    #{:release :implement} ; can reject
   :release   #{:observe}
   :observe   #{:done :implement}    ; can trigger new work
   :done      #{}})

;------------------------------------------------------------------------------ Layer 1
;; Workflow protocol

(defprotocol Workflow
  "Protocol for SDLC workflow execution.
   Manages state transitions through phases."

  (start [this spec context]
    "Start a new workflow from a specification.
     Returns workflow-id.")

  (get-state [this workflow-id]
    "Get current workflow state.
     Returns {:phase :status :artifacts :errors :metrics}")

  (advance [this workflow-id phase-result]
    "Advance workflow based on phase execution result.
     phase-result: {:success? :artifacts :errors}
     Returns updated workflow state.")

  (rollback [this workflow-id target-phase reason]
    "Rollback to a previous phase.
     Returns updated workflow state.")

  (complete [this workflow-id]
    "Mark workflow as complete.
     Returns final workflow state.")

  (fail [this workflow-id error]
    "Mark workflow as failed.
     Returns final workflow state."))

;------------------------------------------------------------------------------ Layer 2
;; Phase executor protocol

(defprotocol PhaseExecutor
  "Protocol for executing individual SDLC phases."

  (execute-phase [this workflow-state context]
    "Execute the current phase.
     Returns {:success? :artifacts :errors :metrics}")

  (can-execute? [this phase]
    "Check if this executor can handle a phase.")

  (get-phase-requirements [this phase]
    "Get required inputs for a phase.
     Returns {:required-artifacts [...] :optional-artifacts [...]}"))

;------------------------------------------------------------------------------ Layer 3
;; Workflow observer protocol (for operator/meta-loop)

(defprotocol WorkflowObserver
  "Protocol for observing workflow events.
   Used by the operator for meta-loop signals."

  (on-phase-start [this workflow-id phase context]
    "Called when a phase starts.")

  (on-phase-complete [this workflow-id phase result]
    "Called when a phase completes.")

  (on-phase-error [this workflow-id phase error]
    "Called when a phase errors.")

  (on-workflow-complete [this workflow-id final-state]
    "Called when entire workflow completes.")

  (on-rollback [this workflow-id from-phase to-phase reason]
    "Called when workflow rolls back."))
