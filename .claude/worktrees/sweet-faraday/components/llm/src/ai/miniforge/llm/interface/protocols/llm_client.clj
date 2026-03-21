(ns ai.miniforge.llm.interface.protocols.llm-client
  "Public protocol for LLM client interaction.

   This is an extensibility point - users can implement custom LLM clients
   by implementing this protocol.")

(defprotocol LLMClient
  "Protocol for LLM interaction."
  (complete* [this request]
    "Send a completion request, returns result map.")
  (complete-stream* [this request on-chunk]
    "Send a streaming completion request, calls on-chunk for each token.

     Arguments:
     - request: Request map (same as complete*)
     - on-chunk: Callback function (fn [chunk-data])
                 Called with {:delta \"...\" :done? false} for each chunk
                 Called with {:delta \"\" :done? true :content \"full text\"} when complete

     Returns: Same format as complete* (final result)")
  (get-config [this]
    "Return client configuration."))
