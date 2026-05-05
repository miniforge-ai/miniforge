;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.llm.protocols.impl.llm-client
  "Implementation functions for LLMClient protocol."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cheshire.core :as json]
   [babashka.process :as p]
   [org.httpkit.client :as http]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.response.interface :as response]
   [slingshot.slingshot :refer [throw+ try+]])
  (:import
   [java.io ByteArrayInputStream]
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;------------------------------------------------------------------------------ Layer 0
;; LLM response builders
;;
;; All LLM responses follow the contract:
;;   Success: {:success true :content string :usage map}
;;   Failure: {:success false :error {:type string :message string} :anomaly anomaly-map}
;;
;; These builders ensure consistent construction across all backends
;; (CLI, HTTP/OpenAI, HTTP/Ollama, streaming, non-streaming).

(defn- load-client-defaults
  []
  (if-let [resource (io/resource "llm/client-defaults.edn")]
    (edn/read-string (slurp resource))
    (response/throw-anomaly! :anomalies/not-found
                             "Missing llm/client-defaults.edn resource")))

(def ^:private client-defaults
  (delay (load-client-defaults)))

(defn- client-default
  [path]
  (get-in @client-defaults path))

(defn- default-claude-cli-budget-usd
  []
  (client-default [:claude-cli :default-budget-usd]))

;; ---------------------------------------------------------------------------
;; Stream-timing constants
;;
;; Names spell out intent so the surrounding code reads as the why,
;; not the what. Tuning history lives in inline doc-comments next to
;; each constant.

(defn- default-stagnation-threshold-ms
  []
  (client-default [:stream :default-stagnation-threshold-ms]))

(defn- default-max-total-ms
  []
  (client-default [:stream :default-max-total-ms]))

(defn- default-min-activity-interval-ms
  []
  (client-default [:stream :default-min-activity-interval-ms]))

(defn- stream-line-timeout-ms
  []
  (client-default [:stream :line-timeout-ms]))

(defn- process-join-timeout-ms
  []
  (client-default [:stream :process-join-timeout-ms]))

(defn- post-kill-join-timeout-ms
  []
  (client-default [:stream :post-kill-join-timeout-ms]))

(defn- stream-poll-interval-ms
  []
  (client-default [:stream :poll-interval-ms]))

(defn llm-success
  "Build a successful LLM response."
  ([content]
   (llm-success content nil))
  ([content {:keys [usage exit-code]}]
   (let [usage (or usage {:input-tokens 0 :output-tokens 0})]
     (cond-> {:success true
              :content content
              :usage usage
              :tokens (+ (get usage :input-tokens 0) (get usage :output-tokens 0))}
       (some? exit-code) (assoc :exit-code exit-code)))))

(defn llm-error
  "Build a failed LLM response with anomaly."
  ([category error-type message]
   (llm-error category error-type message nil))
  ([category error-type message {:keys [exit-code stderr stdout raw-stdout timeout]}]
   (cond-> {:success false
            :error (cond-> {:type error-type :message message}
                     stderr  (assoc :stderr stderr)
                     stdout  (assoc :stdout stdout)
                     raw-stdout (assoc :raw-stdout raw-stdout)
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
                thinking-block? (some (fn [block]
                                        (contains? #{"thinking" "redacted_thinking"}
                                                   (:type block)))
                                      content-blocks)
                tool-names (keep (fn [block]
                                   (when (= "tool_use" (:type block))
                                     (:name block)))
                                 content-blocks)
                text (str/join text-blocks)
                ;; Claude surfaces stop_reason on each assistant message.
                ;; Values: "end_turn" | "max_tokens" | "stop_sequence" |
                ;; "tool_use" | "max_turns" (Claude Code adds the last).
                ;; The accumulator tracks the LATEST — that's the reason
                ;; the overall turn ended.
                stop-reason (get-in data [:message :stop_reason])]
            (cond
              (seq tool-names)
              (cond-> {:delta (or text "") :done? false
                       :tool-use true :tool-names tool-names}
                stop-reason (assoc :stop-reason stop-reason))

              (not (str/blank? text))
              (cond-> {:delta text :done? false}
                stop-reason (assoc :stop-reason stop-reason))

              thinking-block?
              (cond-> {:delta "" :done? false :thinking true}
                stop-reason (assoc :stop-reason stop-reason))

              ;; No text + no tool calls — still carry stop-reason if
              ;; present so "empty turn" shows a reason.
              stop-reason
              {:delta "" :done? false :stop-reason stop-reason}))

          ;; Legacy format support
          "stream_event"
          (when-let [delta-text (get-in data [:event :delta :text])]
            {:delta delta-text :done? false})

          "result"
          (let [usage (or (:usage data) (get-in data [:result :usage]))]
            (cond-> {:delta "" :done? true
                     :usage {:input-tokens (:input_tokens usage)
                             :output-tokens (:output_tokens usage)}
                     :cost-usd (:total_cost_usd data)}
              (:session_id data) (assoc :session-id (:session_id data))
              (:num_turns data)  (assoc :num-turns (:num_turns data))
              ;; Claude Code surfaces a top-level stop_reason on the
              ;; final result event too — prefer it over the per-message
              ;; stop_reason when both are present.
              (:stop_reason data) (assoc :stop-reason (:stop_reason data))))

          ;; Tool use events — capture tool name for diagnostics
          "tool_use"
          (let [tool-name (or (get-in data [:tool :name])
                              (get-in data [:name])
                              (:tool_name data))]
            {:delta "" :done? false :tool-use true
             :tool-name tool-name})

          ;; System init is a no-op.
          "system"
          nil

          ;; Claude emits rate_limit_event while a turn is still alive.
          ;; Treat it as liveness so provider-side pacing does not look
          ;; like a dead stream.
          "rate_limit_event"
          {:delta "" :done? false :heartbeat true}

          ;; Any other unrecognised event type — emit a heartbeat so the
          ;; stream stays alive during tool-heavy phases
          {:delta "" :done? false :heartbeat true})))
    (catch Exception _e
      nil)))

(defn- normalize-codex-finish-reason
  "Normalize a Codex finish_reason string to the canonical stop-reason strings
   used across all backends.

   Codex → canonical mapping:
   - \"stop\"      → \"end_turn\"     (normal completion)
   - \"max_turns\" → \"max_turns\"    (turn budget exhausted)
   - \"length\"    → \"max_tokens\"   (token budget exhausted)
   - anything else → passed through unchanged"
  [finish-reason]
  (when finish-reason
    (case finish-reason
      "stop"      "end_turn"
      "max_turns" "max_turns"
      "length"    "max_tokens"
      finish-reason)))

(defn parse-codex-stream-line
  "Parse a line from Codex CLI streaming output.

   Codex emits JSONL events:
   - thread.started: session began
   - turn.started: turn began
   - item.completed: reasoning, agent_message, mcp_tool_call, etc.
   - turn.completed: carries usage data and optional finish_reason
   - turn.failed / error: failure events

   Diagnostic fields returned on turn.completed:
   - :stop-reason   — normalized finish_reason (\"end_turn\", \"max_turns\", etc.)
   - :increment-turns — true, signalling the accumulator to bump its turn counter

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

              ;; MCP tool invocation — emit a tool-use signal so the
              ;; callback can publish :agent/tool-call with the real name
              "mcp_tool_call"
              {:delta "" :done? false :tool-use true
               :tool-name (or (get-in item [:tool :name])
                              (:tool_name item)
                              (:name item))
               :tool-call-id (:id item)}

              ;; Native tool call (pre-MCP) — same treatment
              "tool_call"
              {:delta "" :done? false :tool-use true
               :tool-name (or (:name item) (:tool_name item))
               :tool-call-id (:id item)}

              ;; reasoning — ignored for content but could be surfaced
              ;; later as :agent/reasoning events if useful
              nil))

          ;; Turn completed carries usage and optional finish_reason.
          ;; :increment-turns signals the stream-with-parser accumulator
          ;; to bump its turn counter (Codex equivalent of num_turns).
          "turn.completed"
          (let [usage (:usage data)
                stop-reason (normalize-codex-finish-reason (:finish_reason data))]
            (cond-> {:delta "" :done? true
                     :increment-turns true
                     :usage {:input-tokens (:input_tokens usage)
                             :output-tokens (:output_tokens usage)}}
              stop-reason (assoc :stop-reason stop-reason)))

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
;; Named argument builders — extracted from the backends config map for clarity.

(defn claude-mcp-allowlist-string
  "Claude CLI's --allowedTools wire format.

   Accepts two entry shapes:
   - `{:mcp/server :mcp/tool}` keyword map → `mcp__<server>__<tool>`
     (MCP tool; server name comes from the mcp-config.json key)
   - Keyword by itself → `(name kw)` (native Claude Code tool,
     e.g. `:Write`)

   Entries are joined with commas. A malformed entry throws via
   `(name nil)` at the call site."
  [mcp-allowed-tools]
  (->> mcp-allowed-tools
       (mapv (fn [t]
               (if (keyword? t)
                 (name t)
                 (let [{:mcp/keys [server tool]} t]
                   (str "mcp__" (name server) "__" (name tool))))))
       (str/join ",")))

(defn- claude-args
  "Build CLI arguments for the Claude backend."
  [{:keys [prompt system max-tokens streaming? mcp-config mcp-allowed-tools
           disallowed-tools supervision budget-usd max-turns model resume]}]
  (let [budget (or budget-usd
                   (when max-tokens (default-claude-cli-budget-usd)))]
    (cond-> ["-p"]
      streaming?                   (conj "--output-format" "stream-json")
      streaming?                   (conj "--verbose")
      mcp-config                   (into ["--mcp-config" mcp-config])
      (seq mcp-allowed-tools)      (into ["--allowedTools" (claude-mcp-allowlist-string mcp-allowed-tools)])
      (seq disallowed-tools)       (into ["--disallowedTools" (str/join "," disallowed-tools)])
      (:settings-path supervision) (into ["--settings" (:settings-path supervision)])
      system                       (into ["--system-prompt" system])
      budget                       (into ["--max-budget-usd" (str budget)])
      max-turns                    (into ["--max-turns" (str max-turns)])
      model                        (into ["--model" model])
      resume                       (into ["--resume" resume])
      true                         (conj prompt))))

(defn- codex-args
  "Build CLI arguments for the Codex backend."
  [{:keys [prompt model system]}]
  (cond-> ["exec"
           "--json"
           "--sandbox=workspace-write"
           "--ask-for-approval=never"
           "--skip-git-repo-check"]
    true   (into ["-c" "approval_policy=never"])
    true   (into ["-c" "mcp_servers.artifact.required=true"])
    model  (into ["-m" model])
    system (into ["-c" (str "system_prompt=" (json/generate-string system))])
    true   (conj prompt)))

(defn- cursor-args
  "Build CLI arguments for the Cursor backend."
  [{:keys [prompt mcp-allowed-tools]}]
  (cond-> ["-p"]
    (seq mcp-allowed-tools) (conj "--approve-mcps")
    true (conj prompt)))

(defn- echo-args
  "Build CLI arguments for the echo (test) backend."
  [{:keys [prompt]}]
  [prompt])

(def backends
  {:claude {:cmd "claude"
            :streaming? true
            :description "Claude via Anthropic CLI"
            :provider "Anthropic"
            :requires-cli? true
            :api-key-var "ANTHROPIC_API_KEY"
            :stream-parser parse-claude-stream-line
            :args-fn claude-args}

   :codex {:cmd "codex"
           :streaming? true
           :description "Codex via Codex CLI"
           :provider "Codex"
           :requires-cli? true
           :api-key-var nil
           :stream-parser parse-codex-stream-line
           :args-fn codex-args}

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
            :args-fn cursor-args}

   :echo {:cmd "echo"
          :streaming? false
          :description "Echo backend for testing"
          :provider "Test"
          :requires-cli? false
          :api-key-var nil
          :args-fn echo-args}})

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

(defn success-response
  ([output exit-code]
   (success-response output exit-code nil))
  ([output exit-code stderr]
  (let [trimmed (str/trim output)]
    (cond
      (str/blank? trimmed)
      (llm-error :anomalies.agent/llm-error "empty_success_output"
                 "CLI backend exited successfully but produced no output"
                 {:exit-code exit-code :stderr stderr :stdout output})

      (rate-limited? trimmed)
      (llm-error :anomalies.agent/rate-limited "rate_limit"
                 (str "Claude CLI rate limited: " trimmed)
                 {:exit-code exit-code :stdout output})

      :else
      (cond-> (llm-success trimmed {:exit-code exit-code})
        (seq stderr) (assoc :stderr stderr))))))

(defn error-response [output exit-code stderr]
  (let [error-message (if (and stderr (str/blank? output)) stderr output)]
    (llm-error :anomalies.agent/llm-error "cli_error" (str/trim error-message)
               {:exit-code exit-code :stderr stderr :stdout output})))

(defn parse-cli-output
  ([output exit-code]
   (parse-cli-output output exit-code nil))
  ([output exit-code stderr]
   (if (zero? exit-code)
     (success-response output exit-code stderr)
     (error-response output exit-code stderr))))

(defn default-progress-monitor []
  (pm/create-progress-monitor
   {:stagnation-threshold-ms  (default-stagnation-threshold-ms)
    :max-total-ms             (default-max-total-ms)
    :min-activity-interval-ms (default-min-activity-interval-ms)}))

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

(def ^:private eof-sentinel
  (Object.))

(defn- open-stream-dump-writer
  []
  (when-let [dump-path (System/getenv "MF_STREAM_DUMP")]
    (java.io.PrintWriter.
     (java.io.FileWriter. dump-path true))))

(defn- stream-read-failure
  [ex]
  (response/from-exception ex))

(defn- enqueue-stream-line!
  [line-queue line]
  (.put line-queue line))

(defn- read-stream-loop!
  [out-reader line-queue]
  (loop []
    (if-some [line (.readLine out-reader)]
      (do (enqueue-stream-line! line-queue line)
          (recur))
      (enqueue-stream-line! line-queue eof-sentinel))))

(defn- start-stream-reader!
  [out-reader line-queue]
  (future
    (try
      (read-stream-loop! out-reader line-queue)
      (catch Exception e
        (enqueue-stream-line! line-queue (stream-read-failure e))))))

(defn- record-stream-line!
  [out-lines dump-writer on-line last-line-at now line]
  (reset! last-line-at now)
  (swap! out-lines conj line)
  (when dump-writer
    (.println dump-writer line)
    (.flush dump-writer))
  (on-line line))

(defn- stream-idle-timeout
  [last-line-at line-timeout-ms out-lines now]
  (when (>= (- now @last-line-at) line-timeout-ms)
    {:type :stream-idle
     :message (str "No stream output for " line-timeout-ms "ms")
     :elapsed-ms (- now @last-line-at)
     :stats {:lines-read (count @out-lines)}}))

(defn- stream-poll-signal
  [line-queue]
  (.poll line-queue
         (stream-poll-interval-ms)
         TimeUnit/MILLISECONDS))

(defn- anomaly-signal?
  [line-or-signal]
  (response/anomaly-map? line-or-signal))

(defn- eof-signal?
  [line-or-signal]
  (identical? line-or-signal eof-sentinel))

(defn- timeout-signal
  [last-line-at line-timeout-ms out-lines]
  {:done? false
   :timeout (stream-idle-timeout last-line-at
                                 line-timeout-ms
                                 out-lines
                                 (System/currentTimeMillis))})

(defn- process-stream-signal
  [line-or-signal out-lines dump-writer on-line last-line-at line-timeout-ms]
  (cond
    (anomaly-signal? line-or-signal)
    (throw+ line-or-signal)

    (eof-signal? line-or-signal)
    {:done? true}

    (some? line-or-signal)
    (do (record-stream-line! out-lines
                             dump-writer
                             on-line
                             last-line-at
                             (System/currentTimeMillis)
                             line-or-signal)
        {:done? false})

    :else
    (timeout-signal last-line-at line-timeout-ms out-lines)))

(defn process-stream-lines [out-reader monitor on-line]
  (let [out-lines (atom [])
        timeout-reason (atom nil)
        line-timeout-ms (stream-line-timeout-ms)
        last-line-at (atom (System/currentTimeMillis))
        dump-writer (open-stream-dump-writer)
        line-queue (LinkedBlockingQueue.)
        reader-future (start-stream-reader! out-reader line-queue)]
    (try+
      (loop []
        (if-let [t (pm/check-timeout monitor)]
          (reset! timeout-reason t)
          (let [{:keys [done? timeout]}
                (process-stream-signal (stream-poll-signal line-queue)
                                       out-lines
                                       dump-writer
                                       on-line
                                       last-line-at
                                       line-timeout-ms)]
            (cond
              done? nil
              timeout (reset! timeout-reason timeout)
              :else (recur)))))
      (finally
        (future-cancel reader-future)
        (try (.close out-reader) (catch Exception _))
        (when dump-writer (.close dump-writer))))
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
  ([cmd on-line {:keys [progress-monitor workdir]}]
   (let [monitor (or progress-monitor (default-progress-monitor))
         empty-stdin (ByteArrayInputStream. (byte-array 0))
         process (apply p/process (cond-> {:err :string :in empty-stdin}
                                          (clean-env) (assoc :env (clean-env))
                                          workdir     (assoc :dir workdir)) cmd)
         out-reader (java.io.BufferedReader.
                     (java.io.InputStreamReader. (:out process)))
         {:keys [lines timeout]} (process-stream-lines out-reader monitor on-line)
         ;; Stream ended with a timeout reason (stream-idle,
         ;; stagnation, or total-max) — the subprocess is hung or
         ;; silent. Kill it so `deref` returns immediately instead of
         ;; waiting 10 min for a process that will not exit.
         _ (when timeout
             (try
               (when-let [^Process jp (:proc process)]
                 (.destroyForcibly jp))
               (catch Exception _ nil)))
         join-timeout (if timeout (post-kill-join-timeout-ms) (process-join-timeout-ms))
         result (deref process join-timeout
                       {:exit -1 :err "Process timed out"})]
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
        {:keys [backend model]} config
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
            request-with-model (cond-> request model (assoc :model model))
            args (args-fn (assoc request-with-model :prompt prompt))
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

(defn- record-parsed-progress!
  [progress-monitor parsed accumulated-content]
  (cond
    (:tool-use parsed)
    (pm/record-activity!
     progress-monitor
     (str "tool-use:"
          (or (:tool-name parsed)
              (some->> (:tool-names parsed) seq sort (str/join ","))
              "unknown")))

    (:heartbeat parsed)
    (pm/record-activity! progress-monitor :stream-heartbeat)

    (:thinking parsed)
    (pm/record-activity! progress-monitor :stream-thinking)

    (contains? parsed :done?)
    (pm/record-activity! progress-monitor :stream-result)

    :else
    (pm/record-chunk! progress-monitor @accumulated-content)))

(defn stream-with-parser
  [stream-parser on-chunk progress-monitor accumulated-content accumulated-usage accumulated-cost accumulated-tools accumulated-session-id accumulated-stop-reason accumulated-turns]
  (fn [line]
    (when-let [parsed (stream-parser line)]
      (when-let [usage (:usage parsed)]
        (swap! accumulated-usage (fn [prev] (merge prev usage))))
      (when-let [cost (:cost-usd parsed)]
        (reset! accumulated-cost cost))
      (when-let [session-id (:session-id parsed)]
        (reset! accumulated-session-id session-id))
      (when-let [sr (:stop-reason parsed)]
        (reset! accumulated-stop-reason sr))
      ;; Claude surfaces num_turns as an absolute count on the result event.
      (when-let [nt (:num-turns parsed)]
        (reset! accumulated-turns nt))
      ;; Codex signals each completed turn via :increment-turns rather than
      ;; emitting an absolute count — bump the accumulator on each one.
      (when (:increment-turns parsed)
        (swap! accumulated-turns (fnil inc 0)))
      (if (or (:tool-use parsed) (:heartbeat parsed) (:thinking parsed))
        ;; Tool-use, heartbeat, and thinking events: track semantic
        ;; liveness even without substantive text deltas.
        (do
          (when-let [tool-name (:tool-name parsed)]
            (swap! accumulated-tools conj tool-name))
          (when-let [tool-names (:tool-names parsed)]
            (swap! accumulated-tools into tool-names))
          (record-parsed-progress! progress-monitor parsed accumulated-content)
          (on-chunk (assoc parsed :content @accumulated-content)))
        ;; Normal text deltas
        (when-let [delta (:delta parsed)]
          (swap! accumulated-content str delta)
          (record-parsed-progress! progress-monitor parsed accumulated-content)
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

(defn- message-preview
  "Return the last up-to-500 characters of content for post-mortem diagnostics.
   Returns nil when content is blank."
  [content]
  (when (seq content)
    (subs content (max 0 (- (count content) 500)))))

(defn streaming-success-response
  "Build a streaming success response with diagnostic metadata.

   Diagnostic fields added to the response map:
   - :stop-reason          — why the model stopped (\"end_turn\", \"max_turns\", etc.)
   - :num-turns            — number of conversation turns consumed
   - :tool-call-count      — total number of tool invocations during the run
   - :final-message-preview — last 500 chars of accumulated content (post-mortem aid)"
  [content exit-code usage cost-usd stop-reason num-turns tool-call-count final-message-preview stderr]
  (if (rate-limited? content)
    (llm-error :anomalies.agent/rate-limited "rate_limit"
               (str "Claude CLI rate limited: " (str/trim content))
               {:exit-code exit-code :stdout content
                :stop-reason stop-reason :num-turns num-turns})
    (cond-> (llm-success content {:exit-code exit-code :usage usage})
      cost-usd                    (assoc :cost-usd cost-usd)
      stop-reason                 (assoc :stop-reason stop-reason)
      num-turns                   (assoc :num-turns num-turns)
      (some? tool-call-count)     (assoc :tool-call-count tool-call-count)
      (seq final-message-preview) (assoc :final-message-preview final-message-preview)
      (seq stderr)                (assoc :stderr stderr))))

(defn- blank-streaming-success?
  "True when the streaming transport exited 0 but produced no useful parsed output.

   This is the Claude failure mode observed in dogfood on 2026-05-02:
   the process exited successfully, emitted no parsed stdout blocks, no tool calls,
   and left the planner with an empty assistant turn. In that case we retry once
   through the non-streaming path before classifying the invocation as failed."
  [exit-code final-content tool-call-count usage]
  (and (zero? exit-code)
       (str/blank? final-content)
       (zero? tool-call-count)
       (nil? usage)))

(defn- silent-stream-timeout?
  "True when the streaming transport timed out before any semantic Claude output
   arrived.

   This is a different failure shape from blank-streaming-success:
   the subprocess stayed alive long enough to hit the adaptive timeout, but the
   parser never observed assistant text, tool-use, or usage/result events. In
   that case we retry once through the non-streaming path before classifying the
   invocation as failed."
  [exit-code timeout-info final-content tool-call-count usage]
  (and (= -1 exit-code)
       timeout-info
       (str/blank? final-content)
       (zero? tool-call-count)
       (nil? usage)))

(defn streaming-error-response
  "Build a streaming error response with diagnostic metadata.

   Diagnostic fields added to the response map (alongside :success/:error/:anomaly):
   - :stop-reason          — last observed stop reason before failure
   - :num-turns            — number of conversation turns consumed
   - :tool-call-count      — total number of tool invocations during the run
   - :final-message-preview — last 500 chars of accumulated content (post-mortem aid)"
  [content exit-code err-result raw-stdout timeout-info stop-reason num-turns tool-call-count final-message-preview]
  (let [error-message (or err-result
                          (when (str/blank? content) "Process failed with no output")
                          "Process failed")
        category (if timeout-info :anomalies/timeout :anomalies.agent/llm-error)
        error-type (if timeout-info "adaptive_timeout" "cli_error")]
    (cond-> (llm-error category error-type (str/trim error-message)
                       {:exit-code exit-code
                        :stderr err-result
                        :stdout content
                        :raw-stdout raw-stdout
                        :timeout timeout-info})
      stop-reason                 (assoc :stop-reason stop-reason)
      num-turns                   (assoc :num-turns num-turns)
      (some? tool-call-count)     (assoc :tool-call-count tool-call-count)
      (seq final-message-preview) (assoc :final-message-preview final-message-preview))))

(defn handle-streaming [client request on-chunk backend-config progress-monitor]
  (let [{:keys [logger config]} client
        stream-fn (or (:stream-exec-fn client) stream-exec-fn)
        {:keys [backend model]} config
        {:keys [cmd args-fn stream-parser]} backend-config
        prompt (build-request-prompt request)
        request-with-model (cond-> request model (assoc :model model))
        args (args-fn (assoc request-with-model :prompt prompt :streaming? true))
        full-cmd (into [cmd] args)
        accumulated-content (atom "")
        accumulated-usage (atom nil)
        accumulated-cost (atom nil)
        accumulated-tools (atom [])
        accumulated-session-id (atom nil)
        accumulated-stop-reason (atom nil)
        accumulated-turns (atom nil)]
    (when logger
      (log/debug logger :system :agent/streaming-prompt-sent
                 {:data {:backend backend
                         :prompt-length (count prompt)}}))
    (let [result (stream-fn
                  full-cmd
                  (stream-with-parser stream-parser on-chunk progress-monitor
                                      accumulated-content accumulated-usage
                                      accumulated-cost accumulated-tools
                                      accumulated-session-id
                                      accumulated-stop-reason accumulated-turns)
                  {:progress-monitor progress-monitor
                   :workdir (:workdir request)})
          exit-code (:exit result)
          timeout-info (:timeout result)
          final-content @accumulated-content
          raw-output (:out result)
          diagnostic-content (if (str/blank? final-content)
                               raw-output
                               final-content)
          tools @accumulated-tools
          session-id @accumulated-session-id
          stop-reason @accumulated-stop-reason
          num-turns @accumulated-turns
          tool-call-count (count tools)
          final-message-preview (message-preview diagnostic-content)
          usage @accumulated-usage
          fallback-reason (cond
                            (blank-streaming-success? exit-code
                                                      final-content
                                                      tool-call-count
                                                      usage)
                            :empty-stream-success

                            (silent-stream-timeout? exit-code
                                                    timeout-info
                                                    final-content
                                                    tool-call-count
                                                    usage)
                            :silent-stream-timeout)
          fallback-response (when fallback-reason
                              (complete-impl client request))]
      (if fallback-response
        (do
          (when (:success fallback-response)
            (on-chunk {:delta (:content fallback-response)
                       :done? true
                       :content (:content fallback-response)}))
          (when logger
            (log/info logger :system :agent/streaming-fallback
                      {:data {:backend backend
                              :reason fallback-reason
                              :stderr (:err result)}}))
          fallback-response)
        (do
          (on-chunk {:delta ""
                     :done? true
                     :content final-content
                     :timeout timeout-info})
          (log-streaming-result logger timeout-info (count final-content))
          (when (and logger (seq tools))
            (log/info logger :system :agent/tools-called
                      {:data {:tools tools :count (count tools)}}))
          (when (and logger stop-reason)
            (log/info logger :system :agent/stop-reason
                      {:data {:stop-reason stop-reason :num-turns num-turns
                              :content-length (count final-content)}}))
          (if (zero? exit-code)
            (cond-> (streaming-success-response final-content exit-code
                                                usage @accumulated-cost
                                                stop-reason num-turns
                                                tool-call-count final-message-preview
                                                (:err result))
              (seq tools) (assoc :tools-called tools)
              session-id  (assoc :session-id session-id))
            (cond-> (streaming-error-response final-content exit-code (:err result)
                                              diagnostic-content
                                              timeout-info stop-reason num-turns
                                              tool-call-count final-message-preview)
              session-id (assoc :session-id session-id))))))))

(defn complete-stream-impl [client request on-chunk]
  (let [{:keys [config]} client
        {:keys [backend]} config
        backend-config (get backends backend)
        {:keys [streaming? cmd api-key-var]} backend-config
        progress-monitor (or (:progress-monitor request)
                             (pm/create-progress-monitor
                              {:stagnation-threshold-ms (default-stagnation-threshold-ms)
                               :max-total-ms (default-max-total-ms)}))]

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

(defn capsule-exec-fn
  "Returns an exec-fn that routes CLI commands through a task capsule executor.
   Used in governed mode so the agent CLI runs inside the Docker/K8s container
   instead of on the host (N11 §6.2).

   Arguments:
   - execute-fn  - function [executor env-id command opts] -> result map
   - executor    - TaskExecutor instance
   - env-id      - environment/container ID
   - workdir     - workspace directory inside capsule"
  [execute-fn executor env-id workdir]
  (fn [cmd]
    (let [command-str (clojure.string/join " " cmd)
          result (execute-fn executor env-id command-str {:workdir workdir})]
      (if (and (map? result) (:data result))
        {:out (get-in result [:data :stdout] "")
         :err (get-in result [:data :stderr] "")
         :exit (get-in result [:data :exit-code] 0)}
        {:out ""
         :err (str "Capsule exec error: " result)
         :exit 1}))))

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
