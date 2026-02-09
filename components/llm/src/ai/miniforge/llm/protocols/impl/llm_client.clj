(ns ai.miniforge.llm.protocols.impl.llm-client
  "Implementation functions for LLMClient protocol."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [babashka.process :as p]
   [babashka.http-client :as http]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.response.interface :as response])
  (:import
   [java.io ByteArrayInputStream]))

;------------------------------------------------------------------------------ Layer 0

(def backends
  {:claude {:cmd "claude"
            :streaming? true
            :description "Claude via Anthropic CLI"
            :provider "Anthropic"
            :requires-cli? true
            :api-key-var "ANTHROPIC_API_KEY"
            :stream-parser (fn [line]
                             (try
                               (when-not (str/blank? line)
                                 (let [data (json/parse-string line true)]
                                   (when (= "stream_event" (:type data))
                                     (when-let [delta-text (get-in data [:event :delta :text])]
                                       {:delta delta-text :done? false}))))
                               (catch Exception _e
                                 nil)))
            :args-fn (fn [{:keys [prompt system max-tokens streaming?]}]
                       (cond-> ["-p"]
                         streaming? (conj "--output-format" "stream-json")
                         streaming? (conj "--include-partial-messages")
                         streaming? (conj "--verbose")
                         system (into ["--system-prompt" system])
                         max-tokens (into ["--max-budget-usd" "0.10"])
                         true (conj prompt)))}

   :codex {:cmd "http"
           :streaming? true
           :description "OpenAI Codex via API"
           :provider "OpenAI"
           :requires-cli? false
           :api-key-var "OPENAI_API_KEY"
           :api-endpoint "https://api.openai.com/v1/chat/completions"
           :default-model "gpt-4-turbo"
           :models ["gpt-4-turbo" "gpt-4" "gpt-3.5-turbo"]}

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

   :cursor {:cmd "cursor-cli"
            :streaming? false
            :description "Cursor AI via CLI"
            :provider "Cursor"
            :requires-cli? true
            :api-key-var nil
            :args-fn (fn [{:keys [prompt]}]
                       ["--prompt" prompt])}

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
  "Make HTTP POST request to LLM API."
  [url headers body]
  (try
    (http/post url
               {:headers headers
                :body (json/generate-string body)
                :throw false})
    (catch Exception e
      {:status 500
       :body (str "HTTP request failed: " (.getMessage e))})))

(defn- parse-openai-response
  "Parse OpenAI API response."
  [response]
  (try
    (let [body (json/parse-string (:body response) true)]
      (if (= 200 (:status response))
        {:success true
         :content (get-in body [:choices 0 :message :content] "")
         :usage {:input-tokens (get-in body [:usage :prompt_tokens])
                 :output-tokens (get-in body [:usage :completion_tokens])}}
        {:success false
         :error {:type "api_error"
                 :message (or (get-in body [:error :message])
                              "Unknown API error")}}))
    (catch Exception e
      {:success false
       :error {:type "parse_error"
               :message (str "Failed to parse response: " (.getMessage e))}})))

(defn- parse-ollama-response
  "Parse Ollama API response."
  [response]
  (try
    (let [body (json/parse-string (:body response) true)]
      (if (= 200 (:status response))
        {:success true
         :content (get-in body [:message :content] "")
         :usage {:input-tokens nil
                 :output-tokens nil}}
        {:success false
         :error {:type "api_error"
                 :message (or (:error body) "Unknown API error")}}))
    (catch Exception e
      {:success false
       :error {:type "parse_error"
               :message (str "Failed to parse response: " (.getMessage e))}})))

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

(defn stream-exec-fn
  ([cmd on-line]
   (stream-exec-fn cmd on-line {}))
  ([cmd on-line {:keys [progress-monitor]}]
   (let [monitor (or progress-monitor (default-progress-monitor))
         empty-stdin (ByteArrayInputStream. (byte-array 0))
         process (apply p/process {:err :string :in empty-stdin} cmd)
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
        result (apply p/shell {:out :string :err :string :continue true :timeout timeout-ms} cmd)]
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
