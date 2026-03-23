(ns ai.miniforge.llm.protocols.impl.llm-client
  "Implementation functions for LLMClient protocol."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [babashka.process :as p]
   [org.httpkit.client :as http]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.response.interface :as response])
  (:import
   [java.io ByteArrayInputStream]))

;------------------------------------------------------------------------------ Layer 0
;; LLM response builders
;;
;; All LLM responses follow the contract:
;;   Success: {:success true :content string :usage map}
;;   Failure: {:success false :error {:type string :message string} :anomaly anomaly-map}
;;
;; These builders ensure consistent construction across all backends
;; (CLI, HTTP/OpenAI, HTTP/Ollama, streaming, non-streaming).

(def ^:private default-claude-cli-budget-usd
  "Default CLI budget cap used when a request is token-bounded but does not
   supply an explicit dollar budget."
  "0.10")

(defn llm-success
  "Build a successful LLM response."
  ([content]
   (llm-success content nil))
  ([content {:keys [usage exit-code]}]
   (let [usage (or usage {:input-tokens nil :output-tokens nil})]
     (cond-> {:success true
              :content content
              :usage usage
              :tokens (+ (or (:input-tokens usage) 0) (or (:output-tokens usage) 0))}
       (some? exit-code) (assoc :exit-code exit-code)))))

(defn llm-error
  "Build a failed LLM response with anomaly."
  ([category error-type message]
   (llm-error category error-type message nil))
  ([category error-type message {:keys [exit-code stderr stdout timeout]}]
   (cond-> {:success false
            :error (cond-> {:type error-type :message message}
                     stderr  (assoc :stderr stderr)
                     stdout  (assoc :stdout stdout)
                     timeout (assoc :timeout timeout))
            :anomaly (response/make-anomaly
                      category message
                      {:anomaly/operation :llm-complete})}
     (some? exit-code) (assoc :exit-code exit-code))))

;------------------------------------------------------------------------------ Layer 0
;; Stream parser functions

(defn parse-claude-stream-line
  "Parse a line from Claude CLI streaming output.

   Claude CLI stream-json emits:
   - {\"type\":\"system\"} — init event (ignored)
   - {\"type\":\"assistant\",\"message\":{\"content\":[{\"text\":\"...\"}]}} — content
   - {\"type\":\"result\"} — final result with usage (ignored)

   Arguments:
     line - String line from stream

   Returns: {:delta string :done? boolean} or nil"
  [line]
  (try
    (when-not (str/blank? line)
      (let [data (json/parse-string line true)]
        (case (:type data)
          "assistant"
          (let [content-blocks (get-in data [:message :content])
                text-blocks (keep (fn [block]
                                    (when (= "text" (:type block))
                                      (:text block)))
                                  content-blocks)
                tool-names (keep (fn [block]
                                   (when (= "tool_use" (:type block))
                                     (:name block)))
                                 content-blocks)
                text (str/join text-blocks)]
            (if (seq tool-names)
              {:delta (or text "") :done? false :tool-use true :tool-names tool-names}
              (when-not (str/blank? text)
                {:delta text :done? false})))

          ;; Legacy format support
          "stream_event"
          (when-let [delta-text (get-in data [:event :delta :text])]
            {:delta delta-text :done? false})

          "result"
          (let [usage (or (:usage data) (get-in data [:result :usage]))]
            {:delta "" :done? true
             :usage {:input-tokens (:input_tokens usage)
                     :output-tokens (:output_tokens usage)}
             :cost-usd (:total_cost_usd data)})

          ;; Tool use events — capture tool name for diagnostics
          "tool_use"
          (let [tool-name (or (get-in data [:tool :name])
                              (get-in data [:name])
                              (:tool_name data))]
            {:delta "" :done? false :tool-use true
             :tool-name tool-name})

          ;; System and rate_limit_event are known no-ops
          ("system" "rate_limit_event")
          nil

          ;; Any other unrecognised event type — emit a heartbeat so the
          ;; stream stays alive during tool-heavy phases
          {:delta "" :done? false :heartbeat true})))
    (catch Exception _e
      nil)))

(defn parse-codex-stream-line
  "Parse a line from Codex CLI streaming output.

   Codex emits JSONL events:
   - thread.started: session began
   - turn.started: turn began
   - item.completed: reasoning, agent_message, mcp_tool_call, etc.
   - turn.completed: carries usage data
   - turn.failed / error: failure events

   Arguments:
     line - String line from stream

   Returns: {:delta string :done? boolean :usage map} or nil"
  [line]
  (try
    (when-not (str/blank? line)
      (let [data (json/parse-string line true)]
        (case (:type data)
          ;; Agent message — the actual text output
          "item.completed"
          (let [item (:item data)
                item-type (:type item)]
            (case item-type
              "agent_message"
              {:delta (str (:text item) "\n") :done? false}

              "mcp_tool_call"
              {:delta "" :done? false}

              ;; reasoning, tool_call, etc. — ignore content
              nil))

          ;; Turn completed carries usage
          "turn.completed"
          (let [usage (:usage data)]
            {:delta "" :done? true
             :usage {:input-tokens (:input_tokens usage)
                     :output-tokens (:output_tokens usage)}})

          ;; Turn failed
          "turn.failed"
          {:delta (str "\nError: " (get-in data [:error :message] "unknown")) :done? true}

          "error"
          {:delta (str "\nError: " (:message data "unknown")) :done? true}

          ;; thread.started, turn.started, item.started — ignore
          nil)))
    (catch Exception _e
      nil)))

;------------------------------------------------------------------------------ Backend configurations

(def backends
  {:claude {:cmd "claude"
            :streaming? true
            :description "Claude via Anthropic CLI"
            :provider "Anthropic"
            :requires-cli? true
            :api-key-var "ANTHROPIC_API_KEY"
            :stream-parser parse-claude-stream-line
            :args-fn (fn [{:keys [prompt system max-tokens streaming? mcp-config mcp-allowed-tools supervision budget-usd max-turns]}]
                       (let [budget (or budget-usd
                                        (when max-tokens default-claude-cli-budget-usd))]
                         (cond-> ["-p"]
                           streaming?                   (conj "--output-format" "stream-json")
                           streaming?                   (conj "--verbose")
                           mcp-config                   (into ["--mcp-config" mcp-config])
                           (seq mcp-allowed-tools)      (into ["--allowedTools" (str/join "," mcp-allowed-tools)])
                           (:settings-path supervision) (into ["--settings" (:settings-path supervision)])
                           system                       (into ["--system-prompt" system])
                           budget                       (into ["--max-budget-usd" (str budget)])
                           max-turns                    (into ["--max-turns" (str max-turns)])
                           true                         (conj prompt))))}

   :codex {:cmd "codex"
           :streaming? true
           :description "Codex via Codex CLI"
           :provider "Codex"
           :requires-cli? true
           :api-key-var nil
           :stream-parser parse-codex-stream-line
           :args-fn (fn [{:keys [prompt model system]}]
                      (cond-> ["exec"
                               "--json"
                               "--full-auto"
                               "--skip-git-repo-check"]
                        model  (into ["-m" model])
                        system (into ["-c" (str "system_prompt=" (json/generate-string system))])
                        true   (conj prompt)))}

   :openai {:cmd "http"
            :streaming? true
            :description "OpenAI GPT-4 via API"
            :provider "OpenAI"
            :requires-cli? false
            :api-key-var "OPENAI_API_KEY"
            :api-endpoint "https://api.openai.com/v1/chat/completions"
            :default-model "gpt-4"
            :models ["gpt-4-turbo" "gpt-4" "gpt-3.5-turbo"]}

   :ollama {:cmd "http"
            :streaming? true
            :description "Local models via Ollama"
            :provider "Ollama"
            :requires-cli? false
            :api-key-var nil
            :api-endpoint "http://localhost:11434/api/chat"
            :default-model "codellama"
            :models ["codellama" "llama2" "mistral"]}

   :cursor {:cmd "agent"
            :streaming? false
            :description "Cursor AI via CLI"
            :provider "Cursor"
            :requires-cli? true
            :api-key-var nil
            :args-fn (fn [{:keys [prompt mcp-allowed-tools]}]
                       (cond-> ["-p"]
                         (seq mcp-allowed-tools) (conj "--approve-mcps")
                         true (conj prompt)))}

   :echo {:cmd "echo"
          :streaming? false
          :description "Echo backend for testing"
          :provider "Test"
          :requires-cli? false
          :api-key-var nil
          :args-fn (fn [{:keys [prompt]}]
                     [prompt])}})

(defn build-messages-prompt [messages]
  (->> messages
       (map (fn [{:keys [role content]}]
              (case role
                "user" content
                "assistant" (str "[Assistant]: " content)
                "system" (str "[System]: " content)
                content)))
       (str/join "\n\n")))

;------------------------------------------------------------------------------ HTTP Backend Support

(defn openai-request-body
  "Build OpenAI API request body."
  [{:keys [prompt messages model max-tokens streaming?]}]
  (let [model (or model "gpt-4-turbo")
        msgs (or messages
                 [{:role "user" :content prompt}])]
    {:model model
     :messages msgs
     :stream (boolean streaming?)
     :max_tokens (or max-tokens 4000)}))

(defn ollama-request-body
  "Build Ollama API request body."
  [{:keys [prompt messages model streaming?]}]
  (let [model (or model "codellama")
        msg-content (or prompt
                        (build-messages-prompt messages))]
    {:model model
     :messages [{:role "user" :content msg-content}]
     :stream (boolean streaming?)}))

(defn http-post-request
  "Make HTTP POST request to LLM API.

   Returns HTTP response map or anomaly map on exception."
  [url headers body]
  (try
    @(http/post url
                {:headers headers
                 :body (json/generate-string body)})
    (catch Exception e
      (response/make-anomaly
       :anomalies/unavailable
       (str "HTTP request failed: " (.getMessage e))
       {:anomaly/operation :http-request
        :anomaly.llm/url url}))))

(defn parse-openai-response
  "Parse OpenAI API response.

   Handles anomaly maps from http-post-request or HTTP response maps."
  [response]
  ;; If response is already an anomaly map, pass it through
  (if (response/anomaly-map? response)
    (llm-error (:anomaly/category response) "http_error" (:anomaly/message response))
    (try
      (let [body (json/parse-string (:body response) true)]
        (if (= 200 (:status response))
          (llm-success (get-in body [:choices 0 :message :content] "")
                       {:usage {:input-tokens (get-in body [:usage :prompt_tokens])
                                :output-tokens (get-in body [:usage :completion_tokens])}})
          (llm-error :anomalies/unavailable "api_error"
                     (get-in body [:error :message] "Unknown API error"))))
      (catch Exception e
        (llm-error :anomalies/fault "parse_error"
                   (str "Failed to parse response: " (.getMessage e)))))))

(defn parse-ollama-response
  "Parse Ollama API response.

   Handles anomaly maps from http-post-request or HTTP response maps."
  [response]
  ;; If response is already an anomaly map, pass it through
  (if (response/anomaly-map? response)
    (llm-error (:anomaly/category response) "http_error" (:anomaly/message response))
    (try
      (let [body (json/parse-string (:body response) true)]
        (if (= 200 (:status response))
          (llm-success (get-in body [:message :content] "")
                       {:usage {:input-tokens (:prompt_eval_count body)
                                :output-tokens (:eval_count body)}})
          (llm-error :anomalies/unavailable "api_error"
                     (get body :error "Unknown API error"))))
      (catch Exception e
        (llm-error :anomalies/fault "parse_error"
                   (str "Failed to parse response: " (.getMessage e)))))))

(defn http-complete
  "Complete request using HTTP API backend."
  [backend-config request api-key]
  (let [{:keys [api-endpoint provider]} backend-config
        headers (cond
                  (= provider "OpenAI")
                  {"Authorization" (str "Bearer " api-key)
                   "Content-Type" "application/json"}

                  (= provider "Ollama")
                  {"Content-Type" "application/json"}

                  :else
                  {"Content-Type" "application/json"})
        body (case provider
               "OpenAI" (openai-request-body request)
               "Ollama" (ollama-request-body request)
               {})
        response (http-post-request api-endpoint headers body)]
    (case provider
      "OpenAI" (parse-openai-response response)
      "Ollama" (parse-ollama-response response)
      (llm-error :anomalies/unsupported "unsupported_backend"
                 (str "HTTP backend not implemented for: " provider)))))

(defn http-stream-complete
  "Complete request using HTTP API with streaming.

   Note: Currently falls back to non-streaming as babashka.http-client
   doesn't support streaming responses yet."
  [backend-config request api-key on-chunk]
  (let [accumulated (atom "")]
    (try
      ;; Note: babashka.http-client doesn't support streaming responses yet
      ;; For now, fall back to non-streaming for HTTP backends
      (let [result (http-complete backend-config (assoc request :streaming? false) api-key)]
        (when (:success result)
          (let [content (:content result)]
            (reset! accumulated content)
            (on-chunk {:delta content :done? true :content content})))
        result)
      (catch Exception e
        (llm-error :anomalies/fault "stream_error"
                   (str "Streaming failed: " (.getMessage e)))))))

(defn rate-limited?
  "Detect Claude CLI rate limit messages in content.
   Claude CLI returns exit 0 but emits a rate limit message as content."
  [content]
  (and (string? content)
       (re-find #"(?i)you've hit your limit|rate limit|resets \d+[ap]m" content)))

(defn success-response [output exit-code]
  (let [trimmed (str/trim output)]
    (if (rate-limited? trimmed)
      (llm-error :anomalies.agent/rate-limited "rate_limit"
                 (str "Claude CLI rate limited: " trimmed)
                 {:exit-code exit-code :stdout output})
      (llm-success trimmed {:exit-code exit-code}))))

(defn error-response [output exit-code stderr]
  (let [error-message (if (and stderr (str/blank? output)) stderr output)]
    (llm-error :anomalies.agent/llm-error "cli_error" (str/trim error-message)
               {:exit-code exit-code :stderr stderr :stdout output})))

(defn parse-cli-output
  ([output exit-code]
   (parse-cli-output output exit-code nil))
  ([output exit-code stderr]
   (if (zero? exit-code)
     (success-response output exit-code)
     (error-response output exit-code stderr))))

(defn default-progress-monitor []
  (pm/create-progress-monitor
   {:stagnation-threshold-ms 120000
    :max-total-ms 600000
    :min-activity-interval-ms 5000}))

(defn format-timeout-error [{:keys [message type elapsed-ms]}]
  (format "Adaptive timeout: %s (type: %s, elapsed: %dms)"
          message (name type) elapsed-ms))

(defn timeout-result [out-lines timeout-reason]
  {:out (str/join "\n" out-lines)
   :err (format-timeout-error timeout-reason)
   :exit -1
   :timeout timeout-reason})

(defn success-result [out-lines process-result]
  {:out (str/join "\n" out-lines)
   :err (:err process-result)
   :exit (:exit process-result)})

;------------------------------------------------------------------------------ Layer 1

(defn read-line-with-timeout [reader timeout-ms]
  (let [read-future (future (.readLine reader))]
    (try
      (deref read-future timeout-ms nil)
      (catch java.util.concurrent.TimeoutException _
        nil))))

(defn process-stream-lines [out-reader monitor on-line]
  (let [out-lines (atom [])
        timeout-reason (atom nil)
        line-timeout-ms 60000]
    (loop []
      (if-let [timeout-check (pm/check-timeout monitor)]
        (reset! timeout-reason timeout-check)
        (when-let [line (read-line-with-timeout out-reader line-timeout-ms)]
          (swap! out-lines conj line)
          (pm/record-chunk! monitor line)
          (on-line line)
          (recur))))
    {:lines @out-lines
     :timeout @timeout-reason}))

(defn clean-env
  "Build environment map without CLAUDECODE to allow nested Claude CLI sessions.
   Returns nil if CLAUDECODE is not set (use default env)."
  []
  (when (System/getenv "CLAUDECODE")
    (into {} (remove (fn [[k _]] (= k "CLAUDECODE"))) (System/getenv))))

(defn stream-exec-fn
  ([cmd on-line]
   (stream-exec-fn cmd on-line {}))
  ([cmd on-line {:keys [progress-monitor]}]
   (let [monitor (or progress-monitor (default-progress-monitor))
         empty-stdin (ByteArrayInputStream. (byte-array 0))
         process (apply p/process (cond-> {:err :string :in empty-stdin}
                                          (clean-env) (assoc :env (clean-env))) cmd)
         out-reader (java.io.BufferedReader.
                     (java.io.InputStreamReader. (:out process)))
         {:keys [lines timeout]} (process-stream-lines out-reader monitor on-line)
         result (deref process 600000 {:exit -1 :err "Process timed out"})]
     (if timeout
       (timeout-result lines timeout)
       (success-result lines result)))))

;------------------------------------------------------------------------------ Layer 2

(defn build-request-prompt [request]
  (or (:prompt request)
      (build-messages-prompt (:messages request))))

(defn log-prompt-sent [logger backend prompt]
  (when logger
    (log/debug logger :system :agent/prompt-sent
               {:data {:backend backend
                       :prompt-length (count prompt)}})))

(defn log-response [logger response]
  (when logger
    (if (:success response)
      (log/debug logger :system :agent/response-received
                 {:data {:response-length (count (:content response))}})
      (log/error logger :system :agent/task-failed
                 {:message "CLI command failed"
                  :data (:error response)}))))

(defn complete-impl [client request]
  (let [{:keys [config logger exec-fn]} client
        {:keys [backend]} config
        backend-config (get backends backend)
        {:keys [cmd args-fn api-key-var]} backend-config]
    (log-prompt-sent logger backend (build-request-prompt request))

    ;; Handle HTTP backends differently from CLI backends
    (if (= cmd "http")
      (let [api-key (when api-key-var (System/getenv api-key-var))]
        (if (and api-key-var (not api-key))
          (llm-error :anomalies/incorrect "missing_api_key"
                     (str "Missing API key: " api-key-var " not set"))
          (let [response (http-complete backend-config request api-key)]
            (log-response logger response)
            response)))

      ;; CLI backend
      (let [prompt (build-request-prompt request)
            args (args-fn (assoc request :prompt prompt))
            full-cmd (into [cmd] args)
            result (exec-fn full-cmd)
            response (parse-cli-output (:out result) (:exit result) (:err result))]
        (log-response logger response)
        response))))

(defn handle-non-streaming-fallback [client request on-chunk]
  (let [result (complete-impl client request)]
    (when (:success result)
      (on-chunk {:delta (:content result)
                 :done? true
                 :content (:content result)}))
    result))

(defn stream-with-parser [stream-parser on-chunk accumulated-content accumulated-usage accumulated-cost accumulated-tools]
  (fn [line]
    (when-let [parsed (stream-parser line)]
      (when-let [usage (:usage parsed)]
        (swap! accumulated-usage (fn [prev] (merge prev usage))))
      (when-let [cost (:cost-usd parsed)]
        (reset! accumulated-cost cost))
      (if (or (:tool-use parsed) (:heartbeat parsed))
        ;; Tool-use and heartbeat events: track tool names and fire on-chunk
        (do
          (when-let [tool-name (:tool-name parsed)]
            (swap! accumulated-tools conj tool-name))
          (when-let [tool-names (:tool-names parsed)]
            (swap! accumulated-tools into tool-names))
          (on-chunk (assoc parsed :content @accumulated-content)))
        ;; Normal text deltas
        (when-let [delta (:delta parsed)]
          (swap! accumulated-content str delta)
          (on-chunk (assoc parsed :content @accumulated-content)))))))

(defn log-streaming-result [logger timeout-info content-length]
  (when logger
    (if timeout-info
      (log/warn logger :system :agent/streaming-timeout
                {:message (:message timeout-info)
                 :data {:type (:type timeout-info)
                        :elapsed-ms (:elapsed-ms timeout-info)
                        :stats (:stats timeout-info)}})
      (log/debug logger :system :agent/streaming-complete
                 {:data {:response-length content-length}}))))

(defn streaming-success-response [content exit-code usage cost-usd]
  (if (rate-limited? content)
    (llm-error :anomalies.agent/rate-limited "rate_limit"
               (str "Claude CLI rate limited: " (str/trim content))
               {:exit-code exit-code :stdout content})
    (cond-> (llm-success content {:exit-code exit-code :usage usage})
      cost-usd (assoc :cost-usd cost-usd))))

(defn streaming-error-response [content exit-code err-result timeout-info]
  (let [error-message (or err-result
                          (when (str/blank? content) "Process failed with no output")
                          "Process failed")
        category (if timeout-info :anomalies/timeout :anomalies.agent/llm-error)
        error-type (if timeout-info "adaptive_timeout" "cli_error")]
    (llm-error category error-type (str/trim error-message)
               {:exit-code exit-code :stderr err-result :stdout content :timeout timeout-info})))

(defn handle-streaming [client request on-chunk backend-config progress-monitor]
  (let [{:keys [logger config]} client
        {:keys [backend]} config
        {:keys [cmd args-fn stream-parser]} backend-config
        prompt (build-request-prompt request)
        args (args-fn (assoc request :prompt prompt :streaming? true))
        full-cmd (into [cmd] args)
        accumulated-content (atom "")
        accumulated-usage (atom nil)
        accumulated-cost (atom nil)
        accumulated-tools (atom [])]
    (when logger
      (log/debug logger :system :agent/streaming-prompt-sent
                 {:data {:backend backend
                         :prompt-length (count prompt)}}))
    (let [result (stream-exec-fn
                  full-cmd
                  (stream-with-parser stream-parser on-chunk accumulated-content accumulated-usage accumulated-cost accumulated-tools)
                  {:progress-monitor progress-monitor})
          exit-code (:exit result)
          timeout-info (:timeout result)
          final-content @accumulated-content
          tools @accumulated-tools]
      (on-chunk {:delta ""
                 :done? true
                 :content final-content
                 :timeout timeout-info})
      (log-streaming-result logger timeout-info (count final-content))
      (when (and logger (seq tools))
        (log/info logger :system :agent/tools-called
                  {:data {:tools tools :count (count tools)}}))
      (if (zero? exit-code)
        (cond-> (streaming-success-response final-content exit-code @accumulated-usage @accumulated-cost)
          (seq tools) (assoc :tools-called tools))
        (streaming-error-response final-content exit-code (:err result) timeout-info)))))

(defn complete-stream-impl [client request on-chunk]
  (let [{:keys [config]} client
        {:keys [backend]} config
        backend-config (get backends backend)
        {:keys [streaming? cmd api-key-var]} backend-config
        progress-monitor (or (:progress-monitor request)
                             (pm/create-progress-monitor
                              {:stagnation-threshold-ms 120000
                               :max-total-ms 600000}))]

    ;; Handle HTTP backends
    (if (= cmd "http")
      (let [api-key (when api-key-var (System/getenv api-key-var))]
        (if (and api-key-var (not api-key))
          (llm-error :anomalies/incorrect "missing_api_key"
                     (str "Missing API key: " api-key-var " not set"))
          (http-stream-complete backend-config request api-key on-chunk)))

      ;; CLI backends
      (if-not streaming?
        (handle-non-streaming-fallback client request on-chunk)
        (handle-streaming client request on-chunk backend-config progress-monitor)))))

(defn get-config-impl [client]
  (:config client))

;------------------------------------------------------------------------------ Layer 3

(defn default-exec-fn [cmd]
  (let [timeout-ms 600000
        empty-stdin (ByteArrayInputStream. (byte-array 0))
        result (apply p/shell (cond-> {:out :string :err :string :continue true
                                       :in empty-stdin :timeout timeout-ms}
                                (clean-env) (assoc :env (clean-env))) cmd)]
    {:out (:out result)
     :err (:err result)
     :exit (:exit result)}))

(defn mock-exec-fn [output & {:keys [exit] :or {exit 0}}]
  (fn [_cmd]
    {:out output
     :err ""
     :exit exit}))

(defn mock-exec-fn-multi [outputs]
  (let [call-count (atom 0)]
    (fn [_cmd]
      (let [idx @call-count
            output (get outputs idx (last outputs))]
        (swap! call-count inc)
        {:out output
         :err ""
         :exit 0}))))
