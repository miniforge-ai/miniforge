(ns ai.miniforge.llm.interface
  "Public API for LLM client using CLI backends.
   Supports claude CLI, cursor CLI, and mock backends."
  (:require
   [ai.miniforge.llm.interface.protocols.llm-client :as p]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.llm.protocols.records.llm-client :as records]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.llm.model-registry :as registry]
   [ai.miniforge.llm.model-selector :as selector]))

;------------------------------------------------------------------------------ Layer 0
;; Backend information

(def backends
  "Available CLI backends."
  impl/backends)

;; Re-export protocol for public API
(def LLMClient p/LLMClient)

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
  ([] (records/create-client))
  ([opts] (records/create-client opts)))

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
                  (impl/mock-exec-fn-multi outputs)
                  (impl/mock-exec-fn (or output "Mock response") :exit exit))]
    (records/create-client {:backend :claude :exec-fn exec-fn})))

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
  (p/complete* client request))

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

(defn complete-stream
  "Send a streaming completion request to the LLM.

   Arguments:
   - client   - Client created by create-client
   - request  - Request map (same as complete)
   - on-chunk - Callback function (fn [chunk-data])
                Called with {:delta \"...\" :done? false :content \"accumulated\"} for each chunk
                Called with {:delta \"\" :done? true :content \"full text\"} when complete

   Returns: Same format as complete (final result)

   Note: Falls back to non-streaming for backends that don't support it.

   Example:
     (complete-stream client
                      {:prompt \"Write a story\"}
                      (fn [{:keys [delta done? content]}]
                        (when-not done?
                          (print delta)
                          (flush))))"
  [client request on-chunk]
  (p/complete-stream* client request on-chunk))

(defn chat-stream
  "Convenience function for streaming single-turn chat.

   Arguments:
   - client   - Client created by create-client
   - prompt   - User message string
   - on-chunk - Callback for streaming chunks
   - opts     - Optional map with :system, :max-tokens

   Example:
     (chat-stream client
                  \"Tell me a story\"
                  (fn [{:keys [delta]}] (print delta)))"
  ([client prompt on-chunk]
   (chat-stream client prompt on-chunk {}))
  ([client prompt on-chunk opts]
   (complete-stream client (assoc opts :prompt prompt) on-chunk)))

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

;------------------------------------------------------------------------------ Layer 3
;; Progress Monitoring

(defn create-progress-monitor
  "Create a progress monitor for tracking workflow activity.

   Options:
   - :stagnation-threshold-ms - Time without progress before stagnation (default: 120000ms)
   - :max-total-ms - Hard timeout limit (default: 600000ms)
   - :min-activity-interval-ms - Minimum time between activities (default: 5000ms)

   Returns: Atom containing monitor state.

   Example:
     (create-progress-monitor {:stagnation-threshold-ms 60000
                               :max-total-ms 300000})"
  [opts]
  (pm/create-progress-monitor opts))

(defn record-chunk!
  "Record a streaming chunk as activity.

   Arguments:
   - monitor - Progress monitor atom (from create-progress-monitor)
   - chunk-content - String content of the chunk

   Returns: Boolean indicating if this was meaningful progress.

   Example:
     (record-chunk! monitor \"Analyzing code...\")"
  [monitor chunk-content]
  (pm/record-chunk! monitor chunk-content))

(defn record-file-write!
  "Record a file write as activity.

   Arguments:
   - monitor - Progress monitor atom
   - file-path - Path of file that was written

   Example:
     (record-file-write! monitor \"src/foo.clj\")"
  [monitor file-path]
  (pm/record-file-write! monitor file-path))

(defn check-timeout
  "Check if monitor has timed out.

   Arguments:
   - monitor - Progress monitor atom

   Returns:
   - nil if still making progress
   - {:type :stagnation|:hard-limit
      :message \"...\"
      :elapsed-ms ...
      :stats {...}} if timed out

   Example:
     (when-let [timeout (check-timeout monitor)]
       (println \"Timeout:\" (:message timeout)))"
  [monitor]
  (pm/check-timeout monitor))

(defn get-stats
  "Get current progress monitor statistics.

   Arguments:
   - monitor - Progress monitor atom

   Returns:
   - {:chunks int
      :unique-chunks int
      :files-written int
      :is-active? boolean
      :time-since-activity-ms int
      :elapsed-ms int}

   Example:
     (let [stats (get-stats monitor)]
       (println \"Chunks:\" (:chunks stats)))"
  [monitor]
  (pm/get-stats monitor))

;------------------------------------------------------------------------------ Layer 4
;; Model Selection API

(defn get-model
  "Get a model's full profile by keyword.

   Example:
     (get-model :opus-4.6)
     (get-model :sonnet-4.5)"
  [model-key]
  (registry/get-model model-key))

(defn get-models-by-capability
  "Get models meeting or exceeding a capability level.

   Example:
     (get-models-by-capability :reasoning :excellent)"
  [capability min-level]
  (registry/get-models-by-capability capability min-level))

(defn get-models-by-use-case
  "Get models that support a specific use-case.

   Example:
     (get-models-by-use-case :code-implementation)"
  [use-case]
  (registry/get-models-by-use-case use-case))

(defn recommend-models-for-task-type
  "Get recommended models for a task type.

   Example:
     (recommend-models-for-task-type :thinking-heavy)"
  [task-type]
  (registry/recommend-models-for-task-type task-type))

(defn select-model
  "Select optimal model based on task classification.

   Arguments:
   - task-classification - Map with :type, :confidence, :reason
   - config - Optional configuration map
   - constraints - Optional constraints map

   Example:
     (select-model {:type :execution-focused
                    :confidence 0.9
                    :reason \"Code implementation\"})"
  ([task-classification]
   (selector/select-model task-classification))
  ([task-classification config]
   (selector/select-model task-classification config))
  ([task-classification config constraints]
   (selector/select-model task-classification config constraints)))

(defn select-model-for-phase
  "Select model for a workflow phase.

   Example:
     (select-model-for-phase :plan)
     (select-model-for-phase :implement)"
  [phase & {:keys [config constraints]}]
  (selector/select-model-for-phase phase :config config :constraints constraints))

(defn explain-selection
  "Generate user-facing explanation of model selection.

   Example:
     (explain-selection selection)"
  [selection]
  (selector/explain-selection selection))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a client with claude CLI (default)
  (def client (create-client))

  ;; Create a client with echo backend (for testing)
  (def client (create-client {:backend :echo}))

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
