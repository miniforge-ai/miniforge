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
(ns ai.miniforge.llm.protocols.impl.llm-client.http-wire
  "Wire shapes for the direct HTTP providers: request-body builders,
   response extractors, and parsing a provider response into the response
   contract."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.llm.messages :as msg]
   [ai.miniforge.llm.protocols.impl.llm-client.prompts :as prompts]
   [ai.miniforge.llm.protocols.impl.llm-client.responses :as responses]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.net HttpURLConnection]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} token-usage
  "Build the canonical usage map, keeping only numeric fields — a
   present-but-nil key defeats downstream `(get usage :input-tokens 0)`
   defaulting (same trap `parsed-usage` guards against).

   `extras` carries the optional fields a backend reports beyond the two
   totals, under these keys:
   - `:cached-input-tokens`       input tokens served from the provider's
                                  prompt cache. On the OpenAI wire shape and
                                  the Codex CLI this is a SUBSET of
                                  `:input-tokens` (unlike the Claude CLI's
                                  `:cache-read-input-tokens`, which is counted
                                  beside `:input-tokens`, never inside it).
   - `:cache-write-input-tokens`  input tokens written to the prompt cache,
                                  also a subset of `:input-tokens`.
   - `:reasoning-output-tokens`   output tokens spent on reasoning, a subset
                                  of `:output-tokens`.
   Kept under their own names so no consumer double-counts them."
  ([input-tokens output-tokens]
   (token-usage input-tokens output-tokens nil))
  ([input-tokens output-tokens extras]
   (reduce-kv (fn [usage k v] (cond-> usage (number? v) (assoc k v)))
              (cond-> {}
                (number? input-tokens)  (assoc :input-tokens input-tokens)
                (number? output-tokens) (assoc :output-tokens output-tokens))
              extras)))

(defn- ^{:stratum 0} parse-response-body
  "Parse a response body as JSON, returning a canonical fault anomaly when
   the JSON library throws. Callers retain the HTTP status alongside this
   value so a non-OK response remains a status failure, not a parse failure."
  [{:keys [status body]}]
  (try+
    (json/parse-string body true)
    (catch Exception e
      (anomaly/exception-anomaly
       :fault
       (msg/t :http-provider.system/parse-failed
              {:reason (ex-message e)})
       {:operation :parse-provider-response
        :status status}
       e))))

;------------------------------------------------------------------------------ Direct provider requests
;; Request/response shaping for the API-key HTTP backends
;; (:anthropic-api / :openai-api / :gemini-api) used where CLI-agent
;; backends cannot run — a sandboxed (Mac App Store) child process
;; inherits the sandbox, so the user's claude/codex CLI wakes up
;; without its config or keychain. Each provider is a body builder +
;; response extractor pair; transport and error shaping are shared
;; (`http-post-request`, `parse-provider-response`).
(defn- ^{:stratum 0} chat-messages
  "Normalize a request to the wire-shape message vector shared by the
   chat-style provider APIs: a `:prompt` string becomes a single user
   message; an explicit `:messages` vector passes through with only
   the wire-relevant keys."
  [{:keys [prompt messages]}]
  (if prompt
    [{:role "user" :content prompt}]
    (mapv (fn [m] (select-keys m [:role :content])) messages)))

(defn- ^{:stratum 0} openai-usage-extras
  "The cache and reasoning breakdown an OpenAI-shaped `usage` block
   carries under its `*_details` maps. Absent on servers that do not
   report them (most local OpenAI-compatible servers)."
  [usage]
  {:cached-input-tokens (get-in usage [:prompt_tokens_details :cached_tokens])
   :cache-write-input-tokens (get-in usage [:prompt_tokens_details :cache_write_tokens])
   :reasoning-output-tokens (get-in usage [:completion_tokens_details :reasoning_tokens])})

;------------------------------------------------------------------------------ HTTP Backend Support
(defn ^{:stratum 0} ollama-request-body
  "Build Ollama API request body. `:num-ctx` sets Ollama's context-window
   option — without it Ollama applies its own small default and silently
   truncates long prompts."
  [{:keys [prompt messages model streaming? num-ctx]}]
  (let [model (or model "codellama")
        msg-content (or prompt
                        (prompts/build-messages-prompt messages))]
    (cond-> {:model model
             :messages [{:role "user" :content msg-content}]
             :stream (boolean streaming?)}
      num-ctx (assoc :options {:num_ctx (long num-ctx)}))))

(defn- ^{:stratum 0} default-anthropic-max-tokens
  []
  (settings/client-default [:http :anthropic :default-max-tokens]))

(defn- ^{:stratum 0} provider-http-error->llm-error
  "Build the canonical failure for a non-OK provider response: the
   category classifies by status class, and the message preserves the
   status alongside the provider's own error text."
  [{:keys [status message]}]
  (responses/llm-error (responses/http-status->category status)
             "api_error"
             (msg/t :http-provider.system/api-error
                    {:status status
                     :message (or message
                                  (msg/t :http-provider.system/unknown-api-error))})))

(defn- ^{:stratum 0} response-parse-error
  "Wrap a canonical JSON-parse anomaly in the LLM failure shape without
   discarding its operation, HTTP status, or exception provenance."
  [parse-anomaly]
  (responses/llm-error :anomalies/fault
             "parse_error"
             (:anomaly/message parse-anomaly)
             {:failure-anomaly parse-anomaly}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} extraction
  "The `{:content :usage}` shape `parse-provider-response` expects from
   every extractor — built in one place so the extractors stay pure
   field mappings. The optional fields:
   - `:usage-extras`  the usage fields beyond the two totals (see
                      `token-usage`).
   - `:cost-usd`      the amount the provider says it billed for the
                      call; when numeric it rides beside `:usage`, never
                      inside it.
   - `:finish-reason` the provider's own word for why generation
                      stopped; it becomes the canonical `:stop-reason`
                      (see `normalize-finish-reason`)."
  [content input-tokens output-tokens {:keys [usage-extras cost-usd finish-reason]}]
  (let [stop-reason (responses/normalize-finish-reason finish-reason)]
    (cond-> {:content content
             :usage (token-usage input-tokens output-tokens usage-extras)}
      (number? cost-usd) (assoc :cost-usd cost-usd)
      stop-reason        (assoc :stop-reason stop-reason))))

(defn ^{:stratum 1} openai-request-body
  "Build an OpenAI Chat Completions request body. The system prompt is
   a leading `system`-role message on this API."
  [{:keys [system model max-tokens] :as request}]
  (cond-> {:model model
           :messages (into (if system [{:role "system" :content system}] [])
                           (chat-messages request))}
    max-tokens (assoc :max_completion_tokens max-tokens)))

(defn ^{:stratum 1} gemini-request-body
  "Build a Gemini generateContent request body. Gemini has no
   assistant role — prior assistant turns map to `model` — and the
   system prompt rides in `systemInstruction`."
  [{:keys [system max-tokens] :as request}]
  (cond-> {:contents (mapv (fn [{:keys [role content]}]
                             {:role (if (= role "assistant") "model" "user")
                              :parts [{:text content}]})
                           (chat-messages request))}
    system     (assoc :systemInstruction {:parts [{:text system}]})
    max-tokens (assoc :generationConfig {:maxOutputTokens max-tokens})))

(defn ^{:stratum 1} parse-ollama-response
  "Parse Ollama API response.

   Handles canonical anomaly maps from http-post-request or HTTP
   response maps. After W2 convergence, http-post-request returns
   `:anomaly/type :unavailable` (no subtype) on failure — the legacy
   category `:anomalies/unavailable` is reconstituted explicitly for
   `llm-error` so downstream classification stays identical."
  [response]
  ;; If response is already a canonical anomaly map, re-wrap it as :anomalies/unavailable so downstream classification stays identical
  (if (anomaly/anomaly? response)
    (responses/llm-error :anomalies/unavailable "http_error" (:anomaly/message response))
    (let [body   (parse-response-body response)
          status (:status response)]
      (cond
        (not= HttpURLConnection/HTTP_OK status)
        (provider-http-error->llm-error
         {:status status
          :message (when-not (anomaly/anomaly? body) (get body :error))})

        (anomaly/anomaly? body)
        (response-parse-error body)

        :else
        (let [stop-reason (responses/normalize-finish-reason (:done_reason body))]
          (cond-> (responses/llm-success (get-in body [:message :content] "")
                               {:usage (token-usage (:prompt_eval_count body)
                                                    (:eval_count body))})
            stop-reason (assoc :stop-reason stop-reason)))))))

(defn ^{:stratum 1} anthropic-request-body
  "Build an Anthropic Messages API request body. `max_tokens` is
   mandatory on this API, so an absent or nil `:max-tokens` falls back
   to the configured default — callers pass {:max-tokens nil} through
   chat/complete opts, and a get-with-default would let that nil reach
   the wire as \"max_tokens\": null (a guaranteed 400)."
  [{:keys [system model] :as request}]
  (cond-> {:model model
           :max_tokens (or (:max-tokens request) (default-anthropic-max-tokens))
           :messages (chat-messages request)}
    system (assoc :system system)))

(defn ^{:stratum 1} parse-provider-response
  "Shared response handling for the direct provider backends: a
   transport-layer anomaly converts to the canonical http_error
   failure; otherwise JSON parse, HTTP status check, then
   provider-specific extraction. `extract-fn` takes the
   parsed body and returns `{:content string :usage usage-map}`, plus
   `:cost-usd` and `:stop-reason` when the provider reported them (see
   `extraction`).

   A blank `:content` on a 200 is surfaced as an error — every caller
   of this path expects generated text (an empty answer with
   `stop_reason: refusal` or a truncated candidate would otherwise
   flow downstream as a silent success).

   `:stop-reason` is on the result whenever the provider gave one, so a
   caller can tell an answer cut at the output cap (`max_tokens`) from a
   complete one (`end_turn`)."
  [extract-fn response]
  (if (anomaly/anomaly? response)
    (responses/llm-error :anomalies/unavailable "http_error" (:anomaly/message response))
    (let [body   (parse-response-body response)
          status (:status response)]
      (cond
        (not= HttpURLConnection/HTTP_OK status)
        (provider-http-error->llm-error
         {:status status
          :message (when-not (anomaly/anomaly? body)
                     (get-in body [:error :message]))})

        (anomaly/anomaly? body)
        (response-parse-error body)

        :else
        (let [{:keys [content usage cost-usd stop-reason]} (extract-fn body)]
          ;; A provider bills a call, and says why generation stopped,
          ;; whether or not it produced text, so both stay on the result
          ;; either way. An empty answer cut at the output cap (every
          ;; token spent on reasoning) and a refusal both arrive as an
          ;; empty 200; the stop reason is what tells them apart.
          (cond-> (if (str/blank? content)
                    (responses/llm-error :anomalies.agent/llm-error "empty_success_output"
                               (msg/t :http-provider.system/no-generated-text))
                    (responses/llm-success content {:usage usage}))
            (number? cost-usd) (assoc :cost-usd cost-usd)
            stop-reason        (assoc :stop-reason stop-reason)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} extract-anthropic
  "Text + usage from an Anthropic Messages response: join the `text`
   content blocks (tool-use and thinking blocks carry no answer text).
   The finish reason is `stop_reason`."
  [body]
  (extraction (->> (:content body)
                   (keep (fn [block] (when (= "text" (:type block)) (:text block))))
                   (str/join))
              (get-in body [:usage :input_tokens])
              (get-in body [:usage :output_tokens])
              {:finish-reason (:stop_reason body)}))

(defn ^{:stratum 2} extract-openai
  "Text + usage from an OpenAI Chat Completions response. The finish
   reason is the first choice's `finish_reason`."
  [body]
  (extraction (get-in body [:choices 0 :message :content])
              (get-in body [:usage :prompt_tokens])
              (get-in body [:usage :completion_tokens])
              {:usage-extras (openai-usage-extras (:usage body))
               :finish-reason (get-in body [:choices 0 :finish_reason])}))

(defn ^{:stratum 2} extract-openrouter
  "Text + usage from an OpenRouter response: the OpenAI shape, plus the
   USD amount OpenRouter billed for the call (`usage.cost`)."
  [body]
  (extraction (get-in body [:choices 0 :message :content])
              (get-in body [:usage :prompt_tokens])
              (get-in body [:usage :completion_tokens])
              {:usage-extras (openai-usage-extras (:usage body))
               :cost-usd (get-in body [:usage :cost])
               :finish-reason (get-in body [:choices 0 :finish_reason])}))

(defn ^{:stratum 2} extract-gemini
  "Text + usage from a Gemini generateContent response: join the text
   parts of the first candidate. The finish reason is that candidate's
   `finishReason`."
  [body]
  (extraction (->> (get-in body [:candidates 0 :content :parts])
                   (keep :text)
                   (str/join))
              (get-in body [:usageMetadata :promptTokenCount])
              (get-in body [:usageMetadata :candidatesTokenCount])
              {:finish-reason (get-in body [:candidates 0 :finishReason])}))

(defn ^{:stratum 2} openrouter-request-body
  "Build an OpenRouter request body: the OpenAI Chat Completions shape,
   with usage accounting requested so the response reports billed cost
   and cached-token counts, and with the configured routing preferences
   as the `provider` object. OpenRouter serves one model from several
   hosts; the preferences decide which hosts may see the prompt."
  [request]
  (assoc (openai-request-body request)
         :usage {:include true}
         :provider (settings/client-default [:http :openrouter :provider])))
