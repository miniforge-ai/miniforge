(ns ai.miniforge.workflow.interface.protocols.workflow-observer
  "Public protocol for observing workflow events.
   This is the extensibility point for operator/meta-loop integration.")

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
