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
;; Stream parser functions

(defn- parse-claude-stream-line
  "Parse a line from Claude CLI streaming output.

   Arguments:
     line - String line from stream

   Returns: {:delta string :done? boolean} or nil"
  [line]
  (try
    (when-not (str/blank? line)
      (let [data (json/parse-string line true)]
        (when (= "stream_event" (:type data))
          (when-let [delta-text (get-in data [:event :delta :text])]
            {:delta delta-text :done? false}))))
    (catch Exception _e
      nil)))

(defn- parse-codex-stream-line
  "Parse a line from Codex CLI streaming output.

   Arguments:
     line - String line from stream

   Returns: {:delta string :done? boolean} or nil"
  [line]
  (try
    (when-not (str/blank? line)
      (let [data (json/parse-string line true)]
        (cond
          ;; Extract content from content_block_delta events
          (= "content_block_delta" (:type data))
          (when-let [delta-text (get-in data [:delta :text])]
            {:delta delta-text :done? false})

          ;; Handle message_delta for streaming text
          (and (= "message_delta" (:type data))
               (get-in data [:delta :text]))
          {:delta (get-in data [:delta :text]) :done? false}

          ;; Ignore other event types
          :else nil)))
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
            :args-fn (fn [{:keys [prompt system max-tokens streaming? mcp-config supervision]}]
                       (cond-> ["-p"]
                         streaming?                   (conj "--output-format" "stream-json")
                         streaming?                   (conj "--verbose")
                         mcp-config                   (into ["--mcp-config" mcp-config])
                         (:settings-path supervision) (into ["--settings" (:settings-path supervision)])
                         system                       (into ["--system-prompt" system])
                         max-tokens                   (into ["--max-budget-usd" "0.10"])
                         true                         (conj prompt)))}

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

(defn- openai-request-body
  "Build OpenAI API request body."
  [{:keys [prompt messages model max-tokens streaming?]}]
  (let [model (or model "gpt-4-turbo")
        msgs (or messages
                 [{:role "user" :content prompt}])]
    {:model model
     :messages msgs
     :stream (boolean streaming?)
     :max_tokens (or max-tokens 4000)}))

(defn- ollama-request-body
  "Build Ollama API request body."
  [{:keys [prompt messages model streaming?]}]
  (let [model (or model "codellama")
        msg-content (or prompt
                        (build-messages-prompt messages))]
    {:model model
     :messages [{:role "user" :content msg-content}]
     :stream (boolean streaming?)}))

(defn- http-post-request
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

(defn- parse-openai-response
  "Parse OpenAI API response.

   Handles anomaly maps from http-post-request or HTTP response maps."
  [response]
  ;; If response is already an anomaly map, pass it through
  (if (response/anomaly-map? response)
    {:success false
     :anomaly response
     :error {:type "http_error"
             :message (:anomaly/message response)}}
    (try
      (let [body (json/parse-string (:body response) true)]
        (if (= 200 (:status response))
          {:success true
           :content (get-in body [:choices 0 :message :content] "")
           :usage {:input-tokens (get-in body [:usage :prompt_tokens])
                   :output-tokens (get-in body [:usage :completion_tokens])}}
          {:success false
           :anomaly (response/make-anomaly
                     :anomalies/unavailable
                     (or (get-in body [:error :message]) "Unknown API error")
                     {:anomaly/operation :llm-api-call
                      :anomaly.llm/status (:status response)})
           :error {:type "api_error"
                   :message (or (get-in body [:error :message])
                                "Unknown API error")}}))
      (catch Exception e
        {:success false
         :anomaly (response/make-anomaly
                   :anomalies/fault
                   (str "Failed to parse response: " (.getMessage e))
                   {:anomaly/operation :llm-response-parse})
         :error {:type "parse_error"
                 :message (str "Failed to parse response: " (.getMessage e))}}))))

(defn- parse-ollama-response
  "Parse Ollama API response.

   Handles anomaly maps from http-post-request or HTTP response maps."
  [response]
  ;; If response is already an anomaly map, pass it through
  (if (response/anomaly-map? response)
    {:success false
     :anomaly response
     :error {:type "http_error"
             :message (:anomaly/message response)}}
    (try
      (let [body (json/parse-string (:body response) true)]
        (if (= 200 (:status response))
          {:success true
           :content (get-in body [:message :content] "")
           :usage {:input-tokens nil
                   :output-tokens nil}}
          {:success false
           :anomaly (response/make-anomaly
                     :anomalies/unavailable
                     (or (:error body) "Unknown API error")
                     {:anomaly/operation :llm-api-call
                      :anomaly.llm/status (:status response)})
           :error {:type "api_error"
                   :message (or (:error body) "Unknown API error")}}))
      (catch Exception e
        {:success false
         :anomaly (response/make-anomaly
                   :anomalies/fault
                   (str "Failed to parse response: " (.getMessage e))
                   {:anomaly/operation :llm-response-parse})
         :error {:type "parse_error"
                 :message (str "Failed to parse response: " (.getMessage e))}}))))

(defn- http-complete
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
      {:success false
       :error {:type "unsupported_backend"
               :message (str "HTTP backend not implemented for: " provider)}})))

(defn- http-stream-complete
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
        {:success false
         :anomaly (response/make-anomaly
                   :anomalies/fault
                   (str "Streaming failed: " (.getMessage e))
                   {:anomaly/operation :llm-stream})
         :error {:type "stream_error"
                 :message (str "Streaming failed: " (.getMessage e))}}))))

(defn- success-response [output exit-code]
  {:success true
   :content (str/trim output)
   :usage {:input-tokens nil :output-tokens nil}
   :exit-code exit-code})

(defn- error-response [output exit-code stderr]
  (let [error-message (if (and stderr (str/blank? output)) stderr output)]
    {:success false
     :error {:type "cli_error"
             :message (str/trim error-message)
             :stderr stderr
             :stdout output}
     :anomaly (response/make-anomaly
               :anomalies.agent/llm-error
               (str/trim error-message)
               {:anomaly/operation :llm-complete})
     :exit-code exit-code}))

(defn parse-cli-output
  ([output exit-code]
   (parse-cli-output output exit-code nil))
  ([output exit-code stderr]
   (if (zero? exit-code)
     (success-response output exit-code)
     (error-response output exit-code stderr))))

(defn- default-progress-monitor []
  (pm/create-progress-monitor
   {:stagnation-threshold-ms 120000
    :max-total-ms 600000
    :min-activity-interval-ms 5000}))

(defn- format-timeout-error [{:keys [message type elapsed-ms]}]
  (format "Adaptive timeout: %s (type: %s, elapsed: %dms)"
          message (name type) elapsed-ms))

(defn- timeout-result [out-lines timeout-reason]
  {:out (str/join "\n" out-lines)
   :err (format-timeout-error timeout-reason)
   :exit -1
   :timeout timeout-reason})

(defn- success-result [out-lines process-result]
  {:out (str/join "\n" out-lines)
   :err (:err process-result)
   :exit (:exit process-result)})

;------------------------------------------------------------------------------ Layer 1

(defn- read-line-with-timeout [reader timeout-ms]
  (let [read-future (future (.readLine reader))]
    (try
      (deref read-future timeout-ms nil)
      (catch java.util.concurrent.TimeoutException _
        nil))))

(defn- process-stream-lines [out-reader monitor on-line]
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

(defn- clean-env
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

(defn- build-request-prompt [request]
  (or (:prompt request)
      (build-messages-prompt (:messages request))))

(defn- log-prompt-sent [logger backend prompt]
  (when logger
    (log/debug logger :system :agent/prompt-sent
               {:data {:backend backend
                       :prompt-length (count prompt)}})))

(defn- log-response [logger response]
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
          {:success false
           :error {:type "missing_api_key"
                   :message (str "Missing API key: " api-key-var " not set")}}
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

(defn- handle-non-streaming-fallback [client request on-chunk]
  (let [result (complete-impl client request)]
    (when (:success result)
      (on-chunk {:delta (:content result)
                 :done? true
                 :content (:content result)}))
    result))

(defn- stream-with-parser [stream-parser on-chunk accumulated-content]
  (fn [line]
    (when-let [parsed (stream-parser line)]
      (when-let [delta (:delta parsed)]
        (swap! accumulated-content str delta)
        (on-chunk (assoc parsed :content @accumulated-content))))))

(defn- log-streaming-result [logger timeout-info content-length]
  (when logger
    (if timeout-info
      (log/warn logger :system :agent/streaming-timeout
                {:message (:message timeout-info)
                 :data {:type (:type timeout-info)
                        :elapsed-ms (:elapsed-ms timeout-info)
                        :stats (:stats timeout-info)}})
      (log/debug logger :system :agent/streaming-complete
                 {:data {:response-length content-length}}))))

(defn- streaming-success-response [content exit-code]
  {:success true
   :content content
   :usage {:input-tokens nil :output-tokens nil}
   :exit-code exit-code})

(defn- streaming-error-response [content exit-code err-result timeout-info]
  (let [error-message (or err-result
                          (when (str/blank? content) "Process failed with no output")
                          "Process failed")
        category (if timeout-info :anomalies/timeout :anomalies.agent/llm-error)]
    {:success false
     :error {:type (if timeout-info "adaptive_timeout" "cli_error")
             :message (str/trim error-message)
             :stderr err-result
             :stdout content
             :timeout timeout-info}
     :anomaly (response/make-anomaly
               category
               (str/trim error-message)
               {:anomaly/operation :llm-stream})
     :exit-code exit-code}))

(defn- handle-streaming [client request on-chunk backend-config progress-monitor]
  (let [{:keys [logger config]} client
        {:keys [backend]} config
        {:keys [cmd args-fn stream-parser]} backend-config
        prompt (build-request-prompt request)
        args (args-fn (assoc request :prompt prompt :streaming? true))
        full-cmd (into [cmd] args)
        accumulated-content (atom "")]
    (when logger
      (log/debug logger :system :agent/streaming-prompt-sent
                 {:data {:backend backend
                         :prompt-length (count prompt)}}))
    (let [result (stream-exec-fn
                  full-cmd
                  (stream-with-parser stream-parser on-chunk accumulated-content)
                  {:progress-monitor progress-monitor})
          exit-code (:exit result)
          timeout-info (:timeout result)
          final-content @accumulated-content]
      (on-chunk {:delta ""
                 :done? true
                 :content final-content
                 :timeout timeout-info})
      (log-streaming-result logger timeout-info (count final-content))
      (if (zero? exit-code)
        (streaming-success-response final-content exit-code)
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
          {:success false
           :error {:type "missing_api_key"
                   :message (str "Missing API key: " api-key-var " not set")}}
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
