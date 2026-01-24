(ns ai.miniforge.agent.protocols.specialized
  "Internal protocol for specialized agents.

   This protocol is internal to the agent component and is not
   intended to be used by other components.")

(defprotocol SpecializedAgent
  "Protocol for specialized AI agents with functional invoke/validate/repair.
   This allows specialized agents to define their behavior via functions."

  (invoke [this context input]
    "Execute the agent's primary function on the input.
     Returns {:status :success/:error, :output <result>, :metrics {...}}")

  (validate [this output]
    "Validate the agent's output against its schema.
     Returns {:valid? bool, :errors [...] or nil}")

  (repair [this output errors context]
    "Attempt to repair invalid output based on validation errors.
     Returns {:status :success/:error, :output <repaired-result>}"))
