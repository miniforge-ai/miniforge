(ns ai.miniforge.workflow.interface.protocols.phase-executor
  "Public protocol for executing individual SDLC phases.
   This is the extensibility point for custom phase executors.")

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
