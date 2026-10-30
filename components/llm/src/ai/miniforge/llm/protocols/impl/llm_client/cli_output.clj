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
(ns ai.miniforge.llm.protocols.impl.llm-client.cli-output
  "Agent CLI output: the Claude and Codex stream-line parsers and the
   accumulator that folds parsed lines, and the classification of CLI
   output into the response contract (buffered and streamed, success and
   error, rate limits, the terminated-by marker)."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.llm.messages :as msg]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.llm.protocols.impl.llm-client.prompts :as prompts]
   [ai.miniforge.llm.protocols.impl.llm-client.responses :as responses]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

;; Stream parser functions
(defn ^{:stratum 0} parsed-usage
  [usage]
  ;; Capture cache fields: a large prompt lands mostly under cache-creation.
  ;; Assoc only numeric fields — a present-but-nil key defeats downstream
  ;; `(get usage :input-tokens 0)` defaulting (NPEs llm-success's :tokens sum).
  (let [input-tokens (:input_tokens usage)
        output-tokens (:output_tokens usage)
        cache-creation (:cache_creation_input_tokens usage)
        cache-read (:cache_read_input_tokens usage)]
    (when (some number? [input-tokens output-tokens cache-creation cache-read])
      (cond-> {}
        (number? input-tokens)   (assoc :input-tokens input-tokens)
        (number? output-tokens)  (assoc :output-tokens output-tokens)
        (number? cache-creation) (assoc :cache-creation-input-tokens cache-creation)
        (number? cache-read)     (assoc :cache-read-input-tokens cache-read)))))

(defn ^{:stratum 0} fold-message-usage
  "Fold one assistant message's usage into the running usage map.

   - Input-side counts (:input-tokens, :cache-*-input-tokens) describe
     that API call's whole context, so the LATEST one is the run's
     current context size. Summing them would inflate
     `context-overflow-by-usage?` into a false overflow on any long run
     that was cut before its result frame.
   - :output-tokens is per call and accumulates.
   - Claude Code emits one `assistant` event per content block, each
     carrying the same message `usage`; `message-id` dedupes those so a
     text + tool_use message folds once. The id rides as metadata so the
     usage map keeps the plain `{:input-tokens .. :output-tokens ..}`
     shape callers compare and serialize."
  [prev message-id usage]
  (let [prev (or prev {})]
    (if (and message-id (= message-id (::message-id (meta prev))))
      prev
      (-> (merge prev (dissoc usage :output-tokens))
          (cond-> (contains? usage :output-tokens)
            (update :output-tokens (fnil + 0) (:output-tokens usage)))
          (vary-meta assoc ::message-id message-id)))))

(defn ^{:stratum 0} rate-limited?
  "Detect Claude CLI rate limit messages in content.
   Claude CLI returns exit 0 but emits a rate limit message as content."
  [content]
  (and (string? content)
       (re-find #"(?i)you've hit your limit|too many requests|\b429\b|rate limit(?:ed| exceeded)?\b|resets \d+[ap]m"
                content)))

(defn ^{:stratum 0} record-parsed-progress!
  [progress-monitor parsed accumulated-content]
  (cond
    (:tool-use parsed)
    (pm/record-activity!
     progress-monitor
     (str "tool-use:"
          (or (:tool-name parsed)
              (some->> (:tool-names parsed) seq sort (str/join ","))
              "unknown")))

    ;; Tool-result events are liveness signals — use a stable activity
    ;; key (not the call-id) so :unique-chunks stays bounded across long
    ;; tool-heavy runs.
    (:tool-result parsed)
    (pm/record-activity! progress-monitor :tool-result)

    (:heartbeat parsed)
    (pm/record-activity! progress-monitor :stream-heartbeat)

    (contains? parsed :done?)
    (pm/record-activity! progress-monitor :stream-result)

    :else
    (pm/record-chunk! progress-monitor @accumulated-content)))

(def ^{:stratum 0} ^:private timeout-type->terminated-by
  "Adaptive-timeout `:type` → the `:llm/terminated-by` marker a cut run
   carries on its response. The marker names the CLIENT-SIDE reason the
   run ended before the model finished, so a role can branch on it
   without re-parsing the timeout envelope. `:hard-limit` is renamed to
   `:max-total` because that is the knob (`:max-total-ms`) an operator
   turns to change it."
  {:hard-limit   :max-total
   :stagnation   :stagnation
   :stream-idle  :stream-idle
   :network-drop :network-drop})

(defn ^{:stratum 0} message-preview
  "Return the last up-to-500 characters of content for post-mortem diagnostics.
   Returns nil when content is blank."
  [content]
  (when (seq content)
    (subs content (max 0 (- (count content) 500)))))

(defn ^{:stratum 0} blank-streaming-success?
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

(defn ^{:stratum 0} parse-codex-stream-line
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
                              (:tool item)
                              (:tool_name item)
                              (:name item))
               :tool-call-id (:id item)}

              ;; Native tool call (pre-MCP) — same treatment
              "tool_call"
              {:delta "" :done? false :tool-use true
               :tool-name (or (:name item) (:tool_name item))
               :tool-call-id (:id item)}

              ;; MCP tool result — emitted when MCP tool output is returned
              ;; to the model. Analogous to Claude's tool_result blocks:
              ;; surface :tool-result so downstream code can pair each
              ;; invocation with its outcome.
              "mcp_tool_result"
              {:delta        ""
               :done?        false
               :tool-result  true
               :tool-call-id (or (:tool_call_id item) (:id item))
               :tool-output  (:output item)
               :tool-error?  (boolean (:is_error item))}

              ;; Native tool result (pre-MCP) — same shape as mcp_tool_result.
              "tool_result"
              {:delta        ""
               :done?        false
               :tool-result  true
               :tool-call-id (or (:tool_call_id item) (:id item))
               :tool-output  (:output item)
               :tool-error?  (boolean (:is_error item))}

              ;; reasoning — ignored for content but could be surfaced
              ;; later as :agent/reasoning events if useful
              nil))

          ;; Turn completed carries usage and optional finish_reason.
          ;; :increment-turns signals the stream-with-parser accumulator
          ;; to bump its turn counter (Codex equivalent of num_turns).
          "turn.completed"
          (let [usage (:usage data)
                stop-reason (responses/normalize-finish-reason (:finish_reason data))]
            (cond-> {:delta "" :done? true
                     :increment-turns true
                     ;; input/output stay present even when nil (existing
                     ;; contract); the cache and reasoning counts ride
                     ;; along only when Codex reports a number.
                     :usage (cond-> {:input-tokens (:input_tokens usage)
                                     :output-tokens (:output_tokens usage)}
                              (number? (:cached_input_tokens usage))
                              (assoc :cached-input-tokens (:cached_input_tokens usage))
                              (number? (:reasoning_output_tokens usage))
                              (assoc :reasoning-output-tokens
                                     (:reasoning_output_tokens usage)))}
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

(defn ^{:stratum 0} error-response [output exit-code stderr]
  (let [error-message (if (and stderr (str/blank? output)) stderr output)]
    (responses/llm-error :anomalies.agent/llm-error "cli_error" (str/trim error-message)
               {:exit-code exit-code :stderr stderr :stdout output})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} terminated-by
  "Return the `:llm/terminated-by` marker for an adaptive-timeout envelope,
   or nil when the run was not cut by the client (no envelope, or an
   envelope type outside the known taxonomy)."
  [timeout-info]
  (get timeout-type->terminated-by (:type timeout-info)))

(defn ^{:stratum 1} parse-claude-stream-line
  "Parse a line from Claude CLI streaming output.

   Claude CLI stream-json emits:
   - {\"type\":\"system\"} — one-shot init event; parsed to a heartbeat
     so it refreshes stream supervision once at session start
   - {\"type\":\"assistant\",\"message\":{\"content\":[{\"text\":\"...\"}]}} — content
   - {\"type\":\"tool_use\"} — tool invocation (carries :tool-use)
   - {\"type\":\"rate_limit_event\"} — periodic liveness pulse from
     the CLI; parsed to a heartbeat so it keeps the progress monitor
     alive while the model is mid-think (no assistant chunks yet)
   - {\"type\":\"result\"} — final usage / stop-reason envelope

   Unrecognised event types also parse to a heartbeat — the stream
   stays alive even when the CLI introduces new event shapes the
   miniforge parser hasn't yet learned.

   Arguments:
     line - String line from stream

   Returns: {:delta string :done? boolean ...} or nil
            (nil for blank lines or parse failures only)"
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
                ;; Collect full tool_use block maps so we can extract :id
                ;; and :input alongside :name. One pass for all three fields.
                tool-blocks (keep (fn [block]
                                    (when (= "tool_use" (:type block)) block))
                                  content-blocks)
                ;; `keep :name` (not `map :name`) drops blocks without a
                ;; :name field so `(seq tool-names)` only goes truthy when
                ;; the assistant turn actually has named tool calls.
                tool-names (keep :name tool-blocks)
                text (str/join text-blocks)
                ;; Claude surfaces stop_reason on each assistant message.
                ;; Values: "end_turn" | "max_tokens" | "stop_sequence" |
                ;; "tool_use" | "max_turns" (Claude Code adds the last).
                ;; The accumulator tracks the LATEST — that's the reason
                ;; the overall turn ended.
                stop-reason (get-in data [:message :stop_reason])
                ;; Per-message usage. The authoritative totals arrive on
                ;; the final `result` frame — which a run cut by an
                ;; adaptive timeout never sees. Carrying each message's
                ;; usage lets the accumulator report what a cut run
                ;; consumed (2026-09-04 trap-bench series 9: two cut
                ;; implement turns logged `:tokens 0`).
                message-usage (parsed-usage (get-in data [:message :usage]))
                message-id (get-in data [:message :id])
                with-usage (fn [parsed]
                             (cond-> parsed
                               message-usage (assoc :message-usage message-usage)
                               (and message-usage message-id)
                               (assoc :message-id message-id)))]
            (cond
              (seq tool-names)
              (let [first-tool (first tool-blocks)]
                (cond-> (with-usage {:delta (or text "") :done? false
                                     :tool-use true :tool-names tool-names})
                  stop-reason               (assoc :stop-reason stop-reason)
                  ;; Additive: :tool-call-id and :tool-input from the first
                  ;; tool_use block. Present only when the fields are
                  ;; non-nil so existing consumers that check for key
                  ;; presence see no change on stripped blocks.
                  (:id first-tool)          (assoc :tool-call-id (:id first-tool))
                  (some? (:input first-tool)) (assoc :tool-input (:input first-tool))))

              (not (str/blank? text))
              (cond-> (with-usage {:delta text :done? false})
                stop-reason (assoc :stop-reason stop-reason))

              ;; No text + no tool calls — still carry stop-reason if
              ;; present so "empty turn" shows a reason.
              stop-reason
              (with-usage {:delta "" :done? false :stop-reason stop-reason})))

          ;; Legacy format support
          "stream_event"
          (when-let [delta-text (get-in data [:event :delta :text])]
            {:delta delta-text :done? false})

          "result"
          (let [usage (parsed-usage (or (:usage data)
                                        (get-in data [:result :usage])))]
            (cond-> {:delta "" :done? true
                     :final-content (:result data)
                     :cost-usd (:total_cost_usd data)}
              usage (assoc :usage usage)
              (:session_id data) (assoc :session-id (:session_id data))
              (:num_turns data)  (assoc :num-turns (:num_turns data))
              ;; Claude Code surfaces a top-level stop_reason on the
              ;; final result event too — prefer it over the per-message
              ;; stop_reason when both are present.
              (:stop_reason data) (assoc :stop-reason (:stop_reason data))))

          ;; User messages contain tool_result content blocks — emitted by
          ;; the CLI when tool output is fed back to the model. Surface a
          ;; :tool-result chunk so downstream subscribers can observe both
          ;; the invocation (:tool-use) and the outcome (:tool-result).
          ;; "user" frames without tool_result (e.g. plain echo) fall
          ;; through to a heartbeat — same shape the case-default would
          ;; have produced before "user" was a recognised type.
          "user"
          (let [content-blocks (get-in data [:message :content])
                tool-result-blocks (when (sequential? content-blocks)
                                     (filter (fn [b] (= "tool_result" (:type b)))
                                             content-blocks))]
            (if (seq tool-result-blocks)
              (let [block       (first tool-result-blocks)
                    raw-content (:content block)
                    tool-output (cond
                                  (string? raw-content)     raw-content
                                  ;; Array of content blocks — join text parts
                                  (sequential? raw-content) (->> raw-content
                                                                 (keep (fn [b]
                                                                         (when (= "text" (:type b))
                                                                           (:text b))))
                                                                 (str/join "\n"))
                                  (nil? raw-content)        ""
                                  :else                     (str raw-content))]
                {:delta        ""
                 :done?        false
                 :tool-result  true
                 :tool-call-id (:tool_use_id block)
                 :tool-output  tool-output
                 :tool-error?  (boolean (:is_error block))})
              {:delta "" :done? false :heartbeat true}))

          ;; Tool use events — capture tool name for diagnostics
          "tool_use"
          (let [tool-name (or (get-in data [:tool :name])
                              (get-in data [:name])
                              (:tool_name data))]
            {:delta "" :done? false :tool-use true
             :tool-name tool-name})

          ;; System init and rate-limit notifications carry no content,
          ;; but they ARE liveness signals from the CLI. While Claude is
          ;; thinking on a heavy first turn (Opus on a multi-thousand-
          ;; token planner prompt can take 30-60s before the first
          ;; assistant chunk), `rate_limit_event` is often the only
          ;; thing arriving on stdout. Treat both as heartbeats so the
          ;; adaptive timeout doesn't fire on a model that's actively
          ;; working — observed in the 2026-05-04 dogfood: 7 rate-limit
          ;; events + 1 system init in 60s, stagnation killed the run
          ;; with zero assistant output produced.
          ("system" "rate_limit_event")
          {:delta "" :done? false :heartbeat true}

          ;; Any other unrecognised event type — emit a heartbeat so the
          ;; stream stays alive during tool-heavy phases
          {:delta "" :done? false :heartbeat true})))
    (catch Exception _e
      nil)))

(defn ^{:stratum 1} stream-with-parser
  [stream-parser on-chunk progress-monitor accumulated-content accumulated-usage accumulated-cost accumulated-tools accumulated-session-id accumulated-stop-reason accumulated-turns]
  (fn [line]
    (when-let [parsed (stream-parser line)]
      ;; Per-message usage folds as it streams (see `fold-message-usage`)
      ;; so a run cut before its result frame still reports what it
      ;; consumed; the result frame's `:usage` then REPLACES those keys
      ;; with the backend's authoritative totals.
      (when-let [message-usage (:message-usage parsed)]
        (swap! accumulated-usage fold-message-usage (:message-id parsed) message-usage))
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
      ;; Claude may surface the final assistant text only on the terminal
      ;; result event. Preserve previously streamed content when present,
      ;; but recover result-only output when no assistant delta arrived.
      (when-let [final-content (:final-content parsed)]
        (when (and (string? final-content)
                   (str/blank? @accumulated-content)
                   (not (str/blank? final-content)))
          (reset! accumulated-content final-content)))
      ;; Codex signals each completed turn via :increment-turns rather than
      ;; emitting an absolute count — bump the accumulator on each one.
      (when (:increment-turns parsed)
        (swap! accumulated-turns (fnil inc 0)))
      (if (or (:tool-use parsed) (:tool-result parsed) (:heartbeat parsed))
        ;; Tool-use, tool-result, and heartbeat events: track tool names and
        ;; fire on-chunk without appending anything to accumulated-content.
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

(defn ^{:stratum 1} success-response
  ([output exit-code]
   (success-response output exit-code nil))
  ([output exit-code stderr]
  (let [trimmed (str/trim output)]
    (cond
      (str/blank? trimmed)
      (responses/llm-error :anomalies.agent/llm-error "empty_success_output"
                 "CLI backend exited successfully but produced no output"
                 {:exit-code exit-code :stderr stderr :stdout output})

      (rate-limited? trimmed)
      (responses/llm-error :anomalies.agent/rate-limited "rate_limit"
                 (str "Claude CLI rate limited: " trimmed)
                 {:exit-code exit-code :stdout output})

      :else
      (cond-> (responses/llm-success trimmed {:exit-code exit-code})
        (seq stderr) (assoc :stderr stderr))))))

(defn ^{:stratum 1} streaming-success-response
  "Build a streaming success response with diagnostic metadata.

   Diagnostic fields added to the response map:
   - :stop-reason          — why the model stopped (\"end_turn\", \"max_turns\", etc.)
   - :num-turns            — number of conversation turns consumed
   - :tool-call-count      — total number of tool invocations during the run
   - :final-message-preview — last 500 chars of accumulated content (post-mortem aid)"
  [content exit-code usage cost-usd stop-reason num-turns tool-call-count final-message-preview stderr]
  (if (rate-limited? content)
    (responses/llm-error :anomalies.agent/rate-limited "rate_limit"
               (str "Claude CLI rate limited: " (str/trim content))
               {:exit-code exit-code :stdout content
                :stop-reason stop-reason :num-turns num-turns})
    (cond-> (responses/llm-success content {:exit-code exit-code :usage usage})
      cost-usd                    (assoc :cost-usd cost-usd)
      stop-reason                 (assoc :stop-reason stop-reason)
      num-turns                   (assoc :num-turns num-turns)
      (some? tool-call-count)     (assoc :tool-call-count tool-call-count)
      (seq final-message-preview) (assoc :final-message-preview final-message-preview)
      (seq stderr)                (assoc :stderr stderr))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} log-streaming-result
  "Log the outcome of a streamed run. A cut run (any adaptive timeout)
   logs at warn with the elapsed time, the ceiling it hit, and the number
   of tool calls the model had already made — the 2026-09-04 trap-bench
   series 9 run (`baseline-trap-a-ry1`) hit the 10-minute `:max-total`
   ceiling twice mid-write and nothing in the run log said so."
  [logger timeout-info content-length tool-call-count]
  (when logger
    (if timeout-info
      (log/warn logger :system :agent/streaming-timeout
                {:message (:message timeout-info)
                 :data (cond-> {:type (:type timeout-info)
                                :terminated-by (terminated-by timeout-info)
                                :elapsed-ms (:elapsed-ms timeout-info)
                                :tool-call-count tool-call-count
                                :stats (:stats timeout-info)}
                         (:max-ms timeout-info)
                         (assoc :max-ms (:max-ms timeout-info)))})
      (log/debug logger :system :agent/streaming-complete
                 {:data {:response-length content-length}}))))

(defn ^{:stratum 2} streaming-error-response
  "Build a streaming error response with diagnostic metadata.

   Diagnostic fields added to the response map (alongside :success/:error/:anomaly):
   - :stop-reason          — last observed stop reason before failure
   - :num-turns            — number of conversation turns consumed
   - :tool-call-count      — total number of tool invocations during the run
   - :final-message-preview — last 500 chars of accumulated content (post-mortem aid)

   `usage` + `context-window` drive context-overflow classification (N12 §4);
   optional — the 9-arity form skips it (for callers without them). When
   `usage` is present it is also carried on the response as `:usage` /
   `:tokens` (same shape `llm-success` emits) so a run that was cut still
   reports the tokens it consumed.

   `:llm/terminated-by` is set whenever `timeout-info` carries a known
   adaptive-timeout type (see `terminated-by`) — `:max-total` for the
   hard ceiling — so roles can refuse to promote partial work from a run
   the client cut, instead of treating it like a model that finished."
  ([content exit-code err-result raw-stdout timeout-info stop-reason num-turns
    tool-call-count final-message-preview]
   (streaming-error-response content exit-code err-result raw-stdout timeout-info
                             stop-reason num-turns tool-call-count
                             final-message-preview nil nil))
  ([content exit-code err-result raw-stdout timeout-info stop-reason num-turns
    tool-call-count final-message-preview usage context-window]
   (let [error-message (or (not-empty (str/trim (or err-result "")))
                           (when-not (str/blank? content) content)
                           (not-empty (str/trim (or raw-stdout "")))
                           (msg/t :streaming-error.system/no-output))
         category (if timeout-info :anomalies/timeout :anomalies.agent/llm-error)
         error-type (cond
                      timeout-info "adaptive_timeout"
                      (prompts/context-overflow-by-usage? usage context-window) prompts/context-overflow-error-type
                      :else "cli_error")]
     (cond-> (responses/llm-error category error-type (str/trim error-message)
                        {:exit-code exit-code
                         :stderr err-result
                         :stdout content
                         :raw-stdout raw-stdout
                         :timeout timeout-info})
       (terminated-by timeout-info) (assoc :llm/terminated-by (terminated-by timeout-info))
       (some? usage)               (assoc :usage usage
                                          :tokens (+ (get usage :input-tokens 0)
                                                     (get usage :output-tokens 0)))
       stop-reason                 (assoc :stop-reason stop-reason)
       num-turns                   (assoc :num-turns num-turns)
       (some? tool-call-count)     (assoc :tool-call-count tool-call-count)
       (seq final-message-preview) (assoc :final-message-preview final-message-preview)))))

(defn ^{:stratum 2} parse-cli-output
  ([output exit-code]
   (parse-cli-output output exit-code nil))
  ([output exit-code stderr]
   (if (zero? exit-code)
     (success-response output exit-code stderr)
     (error-response output exit-code stderr))))
