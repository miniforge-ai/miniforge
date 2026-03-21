(ns ai.miniforge.workflow.interface.protocols.workflow
  "Public protocol for SDLC workflow execution.
   This is the main extensibility point for custom workflow implementations.")

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
