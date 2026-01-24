(ns ai.miniforge.llm.interface.protocols.llm-client
  "Public protocol for LLM client interaction.

   This is an extensibility point - users can implement custom LLM clients
   by implementing this protocol.")

(defprotocol LLMClient
  "Protocol for LLM interaction."
  (complete* [this request]
    "Send a completion request, returns result map.")
  (get-config [this]
    "Return client configuration."))
