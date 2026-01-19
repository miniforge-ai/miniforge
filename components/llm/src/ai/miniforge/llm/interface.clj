(ns ai.miniforge.llm.interface
  "Public API for LLM client using CLI backends.
   Supports claude CLI, cursor CLI, and mock backends."
  (:require
   [ai.miniforge.llm.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Backend information

(def backends
  "Available CLI backends."
  core/backends)

;------------------------------------------------------------------------------ Layer 1
;; Client creation

(defn create-client
  "Create a new LLM client using a CLI backend.

   Options:
   - :backend - Backend keyword (:claude, :cursor, :echo) - default :claude
   - :logger  - Optional logger for request/response logging

   Example:
     (create-client)                    ; uses claude CLI
     (create-client {:backend :cursor}) ; uses cursor CLI
     (create-client {:backend :claude :logger my-logger})"
  ([] (core/create-client))
  ([opts] (core/create-client opts)))

(defn mock-client
  "Create a mock client for testing.

   Options:
   - :output   - Mock output string to return
   - :outputs  - Vector of outputs for sequential calls
   - :exit     - Exit code (default 0)

   Example:
     (mock-client {:output \"Hello!\"})
     (mock-client {:outputs [\"First\" \"Second\" \"Third\"]})"
  [{:keys [output outputs exit] :or {exit 0}}]
  (let [exec-fn (if outputs
                  (core/mock-exec-fn-multi outputs)
                  (core/mock-exec-fn (or output "Mock response") :exit exit))]
    (core/create-client {:backend :claude :exec-fn exec-fn})))

;------------------------------------------------------------------------------ Layer 2
;; Completion API

(defn complete
  "Send a completion request to the LLM.

   Arguments:
   - client  - Client created by create-client or mock-client
   - request - Request map with:
     - :prompt    - Direct prompt string, OR
     - :messages  - Vector of message maps [{:role \"user\" :content \"...\"}]
     - :system    - Optional system prompt string
     - :max-tokens - Optional max tokens (claude backend only)

   Returns:
   - On success: {:success true :content \"...\" :usage {...}}
   - On failure: {:success false :error {:type \"...\" :message \"...\"}}

   Example:
     (complete client {:prompt \"What is 2+2?\"})

     (complete client
               {:system \"You are a helpful coding assistant.\"
                :messages [{:role \"user\" :content \"Write hello world\"}]})"
  [client request]
  (core/complete* client request))

(defn chat
  "Convenience function for simple single-turn chat.

   Arguments:
   - client  - Client created by create-client
   - prompt  - User message string
   - opts    - Optional map with :system, :max-tokens

   Example:
     (chat client \"What is 2+2?\")
     (chat client \"Explain monads\" {:system \"Be concise.\"})"
  ([client prompt]
   (chat client prompt {}))
  ([client prompt opts]
   (complete client (assoc opts :prompt prompt))))

;; Response helpers

(defn success?
  "Check if a response was successful."
  [response]
  (:success response))

(defn get-content
  "Extract content from a successful response."
  [response]
  (:content response))

(defn get-error
  "Extract error details from a failed response."
  [response]
  (:error response))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a client with claude CLI
  (def client (create-client))

  ;; Create a client with cursor CLI
  (def client (create-client {:backend :cursor}))

  ;; Simple chat
  (chat client "What is 2+2?")

  ;; With system prompt
  (chat client "Explain quantum computing"
        {:system "Explain simply, as if to a child."})

  ;; Full completion request
  (complete client
            {:system "You are a Clojure expert."
             :messages [{:role "user" :content "Write a recursive fibonacci"}]
             :max-tokens 1000})

  ;; Mock client for testing
  (def test-client (mock-client {:output "42"}))

  (chat test-client "What is the answer?")
  ;; => {:success true, :content "42", ...}

  ;; Multiple mock responses
  (def multi-client (mock-client {:outputs ["First" "Second" "Third"]}))
  (chat multi-client "1") ;; => "First"
  (chat multi-client "2") ;; => "Second"

  ;; Check response
  (let [resp (chat test-client "Hi")]
    (when (success? resp)
      (println "Got:" (get-content resp))))

  :leave-this-here)
