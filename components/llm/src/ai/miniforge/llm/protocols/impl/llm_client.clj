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
   [org.httpkit.client :as http]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.cost :as cost]
   [ai.miniforge.llm.messages :as msg]
   [ai.miniforge.llm.model-registry :as model-registry]
   [ai.miniforge.llm.network-monitor :as nnm]
   [ai.miniforge.llm.progress-monitor :as pm]
   [ai.miniforge.response.interface :as response]
   [slingshot.slingshot :refer [try+]])
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
;; (CLI, HTTP/Ollama, streaming, non-streaming).

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

(def ^:private category->type
  "W2 convergence map (subset). Maps every legacy `:anomalies/*` and
   `:anomalies.*/*` category this brick actually produces (per
   `(grep '(llm-error ' …)`) to the canonical `:anomaly/type` from the
   runbook (`docs/runbooks/anomaly-convergence.md`). Generic-standard
   categories map to a bare type and emit no subtype; domain categories
   map to a `(type, subtype)` pair where the subtype is the legacy
   category keyword verbatim.

   Only the categories actually emitted today are listed — an unmapped
   category throws `IllegalArgumentException` in `->canonical-anomaly`,
   forcing the table to grow when a new producer site is added (per
   the no-dead-code policy)."
  {:anomalies/fault              :fault
   :anomalies/unavailable        :unavailable
   :anomalies/unsupported        :unsupported
   :anomalies/timeout            :timeout
   :anomalies.agent/llm-error    :fault
   :anomalies.agent/rate-limited :unavailable})

(def ^:private generic-standard-categories
  "Subset of the cognitect-standard categories (per the runbook
   `Generic standard (no subtype)` table) that this brick emits — these
   four map 1:1 to a type and carry no `:anomaly/subtype`. The runbook's
   full set is eight; the other four (`:anomalies/incorrect`,
   `:anomalies/not-found`, `:anomalies/forbidden`, `:anomalies/conflict`)
   are not produced by `llm-error` in this brick today."
  #{:anomalies/fault
    :anomalies/unavailable
    :anomalies/unsupported
    :anomalies/timeout})

(defn- ->canonical-anomaly
  "Build a canonical anomaly map from the brick's legacy category
   keyword + message + extra data. Generic-standard categories produce
   `(anomaly/anomaly type msg data)`; domain categories produce
   `(anomaly/sub-anomaly type category msg data)`.

   `category` MUST be in `category->type`; an unmapped category is a
   programmer error (the table must be updated when new categories
   are introduced)."
  [category message data]
  (let [a-type (or (category->type category)
                   (throw (IllegalArgumentException.
                           (str "Unmapped LLM anomaly category: "
                                (pr-str category)
                                ". Add it to `category->type` in "
                                "ai.miniforge.llm.protocols.impl.llm-client"))))]
    (if (contains? generic-standard-categories category)
      (anomaly/anomaly a-type message data)
      (anomaly/sub-anomaly a-type category message data))))

(defn llm-error
  "Build a failed LLM response with anomaly.

   The `:anomaly` map is canonical (W2 convergence): `:anomaly/type` from
   the runbook, `:anomaly/subtype` set to the original category for
   domain categories (so failure-classifier / response/translate dispatch
   identically). The original `:operation` lives under `:anomaly/data`."
  ([category error-type message]
   (llm-error category error-type message nil))
  ([category error-type message {:keys [exit-code stderr stdout raw-stdout timeout]}]
   (cond-> {:success false
            :error (cond-> {:type error-type :message message}
                     stderr  (assoc :stderr stderr)
                     stdout  (assoc :stdout stdout)
                     raw-stdout (assoc :raw-stdout raw-stdout)
                     timeout (assoc :timeout timeout))
            :anomaly (->canonical-anomaly
                      category message
                      {:operation :llm-complete})}
     (some? exit-code) (assoc :exit-code exit-code))))

;------------------------------------------------------------------------------ Layer 0
;; Stream parser functions

(defn- parsed-usage
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

(defn parse-claude-stream-line
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
                stop-reason (get-in data [:message :stop_reason])]
            (cond
              (seq tool-names)
              (let [first-tool (first tool-blocks)]
                (cond-> {:delta (or text "") :done? false
                         :tool-use true :tool-names tool-names}
                  stop-reason               (assoc :stop-reason stop-reason)
                  ;; Additive: :tool-call-id and :tool-input from the first
                  ;; tool_use block. Present only when the fields are
                  ;; non-nil so existing consumers that check for key
                  ;; presence see no change on stripped blocks.
                  (:id first-tool)          (assoc :tool-call-id (:id first-tool))
                  (some? (:input first-tool)) (assoc :tool-input (:input first-tool))))

              (not (str/blank? text))
              (cond-> {:delta text :done? false}
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

(def ^:private claude-input-format-flag
  "Claude CLI flag that selects how the user prompt is delivered. Paired
   with `claude-input-format-stdin` (the only value we use today), this
   tells the CLI to read the prompt from stdin instead of argv."
  "--input-format")

(def ^:private claude-input-format-stdin
  "Argument value for `claude-input-format-flag` that switches the CLI
   to text-on-stdin input mode."
  "text")

(defn- claude-args
  "Build CLI arguments for the Claude backend.

   Prompt delivery is controlled by `:prompt-via` on the request map:

     :argv  (default) — prompt is conjoined as a positional argv arg.
                        Legacy behavior; kept for tests + backends that
                        do not invoke the planner-sized prompt path.
     :stdin           — prompt is omitted from argv; `--input-format text`
                        tells claude to read the user prompt from stdin.
                        Required for the planner / implementer path
                        because system-prompt + spec text +
                        existing-files context regularly exceeds POSIX
                        ARG_MAX once combined into argv (observed
                        2026-05-07 stage-3 dogfood: \"Argument list too
                        long\" on plan phase).

   Callers (handle-streaming / complete-impl) read `:prompt-via` from
   the backend config and thread it into the request map before
   invoking this fn; for :stdin they additionally pipe the prompt
   string into the process via the exec layer's `:stdin` option."
  [{:keys [prompt system max-tokens streaming? mcp-config mcp-allowed-tools
           disallowed-tools supervision budget-usd max-turns model resume
           prompt-via]
    :or   {prompt-via :argv}}]
  (let [budget (or budget-usd
                   (when max-tokens (default-claude-cli-budget-usd)))
        stdin? (= prompt-via :stdin)]
    (cond-> ["-p"]
      streaming?                   (conj "--output-format" "stream-json")
      streaming?                   (conj "--verbose")
      stdin?                       (conj claude-input-format-flag
                                         claude-input-format-stdin)
      ;; --strict-mcp-config: only load the MCP servers from our
      ;; --mcp-config, ignoring the host's user/project MCP config. On
      ;; the local worktree executor the agent CLI otherwise inherits the
      ;; operator's personal ~/.claude MCP servers (e.g. Gmail / Calendar
      ;; / Drive), whose tool schemas bloat the agent's fixed context
      ;; overhead. On 2026-06-07 that leakage pushed the planner's
      ;; first-turn prompt past the 200k window — a single ToolSearch
      ;; result tipped it over and the CLI returned "Prompt is too long"
      ;; (plan phase, review-redirect-convergence dogfood adhoc-2135293220).
      ;; The container executor is already isolated from host config; this
      ;; brings the local runner to parity for the MCP vector. Mirrors the
      ;; codex builder's --ignore-user-config.
      mcp-config                   (into ["--mcp-config" mcp-config
                                          "--strict-mcp-config"])
      (seq mcp-allowed-tools)      (into ["--allowedTools" (claude-mcp-allowlist-string mcp-allowed-tools)])
      (seq disallowed-tools)       (into ["--disallowedTools" (str/join "," disallowed-tools)])
      (:settings-path supervision) (into ["--settings" (:settings-path supervision)])
      system                       (into ["--system-prompt" system])
      budget                       (into ["--max-budget-usd" (str budget)])
      max-turns                    (into ["--max-turns" (str max-turns)])
      model                        (into ["--model" model])
      resume                       (into ["--resume" resume])
      (not stdin?)                 (conj prompt))))

(defn- codex-args
  "Build CLI arguments for the Codex backend."
  [{:keys [prompt model system prompt-via]
    :or {prompt-via :stdin}}]
  (let [stdin? (= prompt-via :stdin)]
    (cond-> ["exec"
             "--json"
             "--ignore-user-config"
             "--sandbox=workspace-write"
             "--skip-git-repo-check"]
      true   (into ["-c" "approval_policy=never"])
      model  (into ["-m" model])
      system (into ["-c" (str "system_prompt=" (json/generate-string system))])
      stdin? (conj "-")
      (not stdin?) (conj prompt))))

(defn- cursor-args
  "Build CLI arguments for the Cursor backend (binary `agent`).

   Flags follow https://cursor.com/docs/cli/reference/parameters:

     -p / --print  non-interactive run with tool access
     --force       required for the agent to write files in print mode;
                   this is the autonomous-execution switch (analogous to
                   codex `approval_policy=never`)

   Tool restriction is enforced out-of-band via the default-deny
   .cursor/cli.json permissions allowlist written by the agent session
   (see artifact-session/write-cursor-permissions!), not via CLI flags —
   so we do not pass --approve-mcps (which would blanket-approve every MCP
   server). Cursor exposes no system-prompt flag, so `system` is prepended
   to the user prompt."
  [{:keys [prompt system model]}]
  (let [prompt (if system (str system "\n\n" prompt) prompt)]
    (cond-> ["-p" "--force"]
      model (into ["--model" model])
      true  (conj prompt))))

(defn- opencode-args
  "Build CLI arguments for the OpenCode backend.

   OpenCode owns provider credentials, model aliases, project config,
   agent profiles, MCP config, and permissions. Miniforge passes the
   selected model/profile knobs through and treats OpenCode as the
   common provider wrapper instead of handling API keys directly."
  [{:keys [prompt model agent attach command session continue? fork? files format]}]
  (cond-> ["run"]
    attach    (into ["--attach" attach])
    command   (into ["--command" command])
    continue? (conj "--continue")
    session   (into ["--session" session])
    fork?     (conj "--fork")
    model     (into ["--model" model])
    agent     (into ["--agent" agent])
    (seq files) (into (mapcat (fn [file] ["--file" file]) files))
    format    (into ["--format" format])
    true      (conj prompt)))

(defn- echo-args
  "Build CLI arguments for the echo (test) backend."
  [{:keys [prompt]}]
  [prompt])

;; `:probe-endpoint` on each backend entry is the stable provider edge
;; URL that the network-monitor (PR-B) probes via
;; `network-health/network-healthy?`. We only care that the connection
;; completed, not the response status — a 4xx is the typical case (HEAD
;; against api.anthropic.com unauthenticated returns 405). Backends
;; without a provider-controlled endpoint (`:echo`) get the generic
;; Cloudflare DNS connectivity URL so the monitor still has something
;; to probe.
(def ^:private generic-connectivity-probe-url
  "https://1.1.1.1/")

;; Pure-data backend fields (`:cmd`, endpoints, `:default-model`,
;; `:models`, etc.) live in `llm/backends.edn`; the function-valued
;; fields (`:stream-parser`, `:args-fn`) stay here because they
;; reference parser/arg-builder fns defined in this namespace. The two
;; are merged per backend below to form `backends`.
(defn- load-backend-data
  []
  (if-let [resource (io/resource "llm/backends.edn")]
    (edn/read-string (slurp resource))
    (response/throw-anomaly! :anomalies/not-found
                             "Missing llm/backends.edn resource")))

(def ^:private backend-data
  (load-backend-data))

;; Function-valued backend fields. The :ollama entry is intentionally
;; absent (it has none); OpenCode loads credentials from its auth
;; store, environment, or project .env/config, so Miniforge should not
;; read provider-specific keys on that path.
(def ^:private backend-fns
  {:claude {:stream-parser parse-claude-stream-line
            :args-fn claude-args}
   :codex {:stream-parser parse-codex-stream-line
           :args-fn codex-args}
   :cursor {:args-fn cursor-args}
   :opencode {:args-fn opencode-args}
   :echo {:args-fn echo-args}})

(def backends
  (merge-with merge backend-data backend-fns))

(defn probe-endpoint-for
  "Resolve the network-health probe URL for `backend-key`. Returns the
   backend entry's `:probe-endpoint` when set, the generic Cloudflare
   DNS connectivity URL otherwise (covers unknown backends and any
   future entry added without an explicit endpoint).

   The caller (e.g. `stream-exec-fn` in PR-B) resolves the URL here and
   passes it to `network-health/network-healthy?` directly, keeping
   that namespace endpoint-agnostic and free of backend-config
   dependencies."
  [backend-key]
  (or (get-in backends [backend-key :probe-endpoint])
      generic-connectivity-probe-url))

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

(defn ollama-request-body
  "Build Ollama API request body."
  [{:keys [prompt messages model streaming?]}]
  (let [model (or model "codellama")
        msg-content (or prompt
                        (build-messages-prompt messages))]
    {:model model
     :messages [{:role "user" :content msg-content}]
     :stream (boolean streaming?)}))

(defn- http-failure-anomaly
  "Build the canonical `:unavailable` anomaly that this brick emits for
   any HTTP-layer failure on an LLM call (thrown exception or http-kit
   `{:error <Throwable>}` deref). Centralizes the shape so the throwing
   and non-throwing failure modes converge."
  [^Throwable cause url]
  (anomaly/anomaly
   :unavailable
   (str "HTTP request failed: " (.getMessage cause))
   {:operation :http-request
    :url url}))

(defn http-post-request
  "Make HTTP POST request to LLM API.

   Returns HTTP response map or canonical anomaly map on failure
   (W2 convergence: `:anomaly/type :unavailable`, no subtype since
   `:anomalies/unavailable` is a generic-standard category).

   http-kit signals connection failures via either a thrown exception
   or a `{:error <Throwable>}` response map (depending on whether the
   error surfaces before or after the future resolves); both modes
   collapse to the same canonical anomaly."
  [url headers body]
  (try
    (let [response @(http/post url
                               {:headers headers
                                :body (json/generate-string body)})]
      (if-let [cause (:error response)]
        (http-failure-anomaly cause url)
        response))
    (catch Exception e
      (http-failure-anomaly e url))))

(defn parse-ollama-response
  "Parse Ollama API response.

   Handles canonical anomaly maps from http-post-request or HTTP
   response maps. After W2 convergence, http-post-request returns
   `:anomaly/type :unavailable` (no subtype) on failure — the legacy
   category `:anomalies/unavailable` is reconstituted explicitly for
   `llm-error` so downstream classification stays identical."
  [response]
  ;; If response is already a canonical anomaly map, pass it through
  (if (anomaly/anomaly? response)
    (llm-error :anomalies/unavailable "http_error" (:anomaly/message response))
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
  "Complete request using an HTTP backend.

   HTTP remains for local, credential-free backends such as Ollama. Provider
   API-key routing for agent runs goes through OpenCode instead of direct HTTP
   calls from miniforge."
  [backend-config request]
  (let [{:keys [api-endpoint provider]} backend-config
        headers {"Content-Type" "application/json"}
        body (case provider
               "Ollama" (ollama-request-body request)
               {})
        response (http-post-request api-endpoint headers body)]
    (case provider
      "Ollama" (parse-ollama-response response)
      (llm-error :anomalies/unsupported "unsupported_backend"
                 (str "HTTP backend not implemented for: " provider)))))

(defn http-stream-complete
  "Complete request using HTTP API with streaming.

   Note: Currently falls back to non-streaming as babashka.http-client
   doesn't support streaming responses yet."
  [backend-config request on-chunk]
  (let [accumulated (atom "")]
    (try
      ;; Note: babashka.http-client doesn't support streaming responses yet
      ;; For now, fall back to non-streaming for HTTP backends
      (let [result (http-complete backend-config (assoc request :streaming? false))]
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
       (re-find #"(?i)you've hit your limit|too many requests|\b429\b|rate limit(?:ed| exceeded)?\b|resets \d+[ap]m"
                content)))

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

(defn stream-anomaly-result [out-lines stream-anomaly]
  {:out (str/join "\n" out-lines)
   :err (:anomaly/message stream-anomaly)
   :exit -1
   :anomaly stream-anomaly})

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
  (anomaly/exception-anomaly :fault (or (ex-message ex) (str (type ex))) ex))

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

(def ^:private stream-reader-join-timeout-ms
  "How long process-stream-lines waits for its daemon reader thread to
   exit after the stream reader is closed."
  100)

(defn- daemon-thread!
  [thread-name f]
  (let [thread (Thread. ^Runnable f thread-name)]
    (.setDaemon thread true)
    (.start thread)
    thread))

(defn- start-stream-reader!
  "Start the daemon that drains `out-reader` into `line-queue`.

   Three exit shapes:
   1. EOF — `read-stream-loop!` enqueues the eof sentinel and returns; thread
      exits cleanly.
   2. Clean shutdown via `stop-stream-reader!` — the consumer
      (`process-stream-lines`) has stopped polling, so `stop-stream-reader!`
      closes the reader and calls `.interrupt`. `BufferedReader.readLine`
      is NOT interruptible, so a reader blocked there unblocks via the
      `.close` (the read returns nil or throws `IOException`). A reader
      blocked in `.put` on a full queue unblocks via the `.interrupt` and
      raises `InterruptedException`. Both cases land in the same silent
      catch: the consumer already left the queue; no anomaly to publish.
   3. Real read error — surface as a `stream-read-failure` anomaly so the
      consumer sees it. The inner try/catch around the anomaly enqueue
      guards against a sticky-interrupt race during cleanup (the thread's
      interrupt flag is still set from case 2, so `.put` here can throw IE
      too — swallow it; the consumer is already gone).

   Pre-fix, case 2 was caught by the generic `(catch Exception e ...)` which
   then called `enqueue-stream-line!` → `.put` → a second IE that bubbled out
   of the runnable to the JVM default exception handler and printed an ugly
   stack trace to stderr. The 2026-06-04 eliminate-requiring-resolve dogfood
   surfaced 18 of these IEs across 20 sub-tasks — pure log noise, zero
   workflow impact, but enough to look like a real bug in post-run triage."
  [out-reader line-queue]
  (daemon-thread!
   "llm-stream-reader"
   (fn []
     (try
       (read-stream-loop! out-reader line-queue)
       (catch InterruptedException _
         ;; clean shutdown — consumer already exited; nothing to report
         nil)
       (catch Exception e
         (try
           (enqueue-stream-line! line-queue (stream-read-failure e))
           (catch InterruptedException _
             ;; the stop-stream-reader! interrupt raced this catch
             nil)))))))

(defn- stop-stream-reader!
  [^Thread reader-thread out-reader]
  (try (.close out-reader) (catch Exception _))
  (.interrupt reader-thread)
  (.join reader-thread stream-reader-join-timeout-ms))

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
  (anomaly/anomaly? line-or-signal))

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
    {:done? true
     :anomaly line-or-signal}

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
        stream-anomaly (atom nil)
        line-timeout-ms (stream-line-timeout-ms)
        last-line-at (atom (System/currentTimeMillis))
        dump-writer (open-stream-dump-writer)
        line-queue (LinkedBlockingQueue.)
        reader-thread (start-stream-reader! out-reader line-queue)]
    (try+
      (loop []
        (if-let [t (pm/check-timeout monitor)]
          (reset! timeout-reason t)
          (let [{:keys [done? timeout anomaly]}
                (process-stream-signal (stream-poll-signal line-queue)
                                       out-lines
                                       dump-writer
                                       on-line
                                       last-line-at
                                       line-timeout-ms)]
            (cond
              anomaly (reset! stream-anomaly anomaly)
              done? nil
              timeout (reset! timeout-reason timeout)
              :else (recur)))))
      (finally
        (stop-stream-reader! reader-thread out-reader)
        (when dump-writer (.close dump-writer))))
    {:lines @out-lines
     :timeout @timeout-reason
     :anomaly @stream-anomaly}))

(defn clean-env
  "Build environment map without CLAUDECODE to allow nested Claude CLI sessions.
   Returns nil if CLAUDECODE is not set (use default env)."
  []
  (when (System/getenv "CLAUDECODE")
    (into {} (remove (fn [[k _]] (= k "CLAUDECODE"))) (System/getenv))))

(defn- ->stdin-stream
  "Build the InputStream passed as the subprocess's stdin. A non-blank
   :stdin string becomes a ByteArrayInputStream over its UTF-8 bytes;
   absent or blank stdin maps to a zero-length stream so the
   subprocess sees EOF immediately (legacy behavior for argv backends)."
  [stdin-str]
  (if (and (string? stdin-str) (not (.isEmpty ^String stdin-str)))
    (ByteArrayInputStream. (.getBytes ^String stdin-str "UTF-8"))
    (ByteArrayInputStream. (byte-array 0))))

(defn- apply-process-env!
  [^ProcessBuilder builder env]
  (when env
    (let [target-env (.environment builder)]
      (.clear target-env)
      (doseq [[k v] env]
        (.put target-env k v)))))

(defn- write-process-stdin!
  [^Process process stdin-str]
  (daemon-thread!
   "llm-stream-stdin-writer"
   (fn []
     (try
       (with-open [out (.getOutputStream process)
                   in (->stdin-stream stdin-str)]
         (io/copy in out))
       (catch Exception _
         nil)))))

(defn- read-process-stream!
  [thread-name input-stream output]
  (daemon-thread!
   thread-name
   (fn []
     (try
       (reset! output (slurp input-stream))
       (catch Exception e
         (reset! output (or (ex-message e) (str e))))))))

(defn- read-process-stderr!
  [^Process process stderr]
  (read-process-stream! "llm-stream-stderr-reader"
                        (.getErrorStream process)
                        stderr))

(defn- start-stream-process!
  [cmd {:keys [workdir stdin]}]
  (let [builder (ProcessBuilder. ^java.util.List cmd)]
    (when-let [env (clean-env)]
      (apply-process-env! builder env))
    (when workdir
      (.directory builder (io/file workdir)))
    (let [process (.start builder)
          stderr (atom "")
          stdin-thread (write-process-stdin! process stdin)
          stderr-thread (read-process-stderr! process stderr)]
      {:process process
       :stderr stderr
       :stdin-thread stdin-thread
       :stderr-thread stderr-thread})))

(defn- start-capture-process!
  [cmd {:keys [workdir stdin]}]
  (let [builder (ProcessBuilder. ^java.util.List cmd)]
    (when-let [env (clean-env)]
      (apply-process-env! builder env))
    (when workdir
      (.directory builder (io/file workdir)))
    (let [process (.start builder)
          stdout (atom "")
          stderr (atom "")
          stdin-thread (write-process-stdin! process stdin)
          stdout-thread (read-process-stream! "llm-stdout-reader"
                                             (.getInputStream process)
                                             stdout)
          stderr-thread (read-process-stream! "llm-stderr-reader"
                                             (.getErrorStream process)
                                             stderr)]
      {:process process
       :stdout stdout
       :stderr stderr
       :stdin-thread stdin-thread
       :stdout-thread stdout-thread
       :stderr-thread stderr-thread})))

(defn- wait-for-stream-process
  [^Process process timeout-ms]
  (if (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
    {:exit (.exitValue process)}
    {:exit -1 :err "Process timed out"}))

(defn- stop-stream-process!
  [{:keys [^Process process stdin-thread stderr-thread stderr]} timeout? join-timeout]
  (when timeout?
    (try (.destroyForcibly process) (catch Exception _ nil)))
  (let [result (wait-for-stream-process process join-timeout)]
    (when stdin-thread
      (.join ^Thread stdin-thread stream-reader-join-timeout-ms))
    (when stderr-thread
      (.join ^Thread stderr-thread stream-reader-join-timeout-ms))
    (assoc result :err @stderr)))

(defn- stop-capture-process!
  [{:keys [^Process process stdin-thread stdout-thread stderr-thread stdout stderr]}
   timeout-ms]
  (let [initial-result (wait-for-stream-process process timeout-ms)
        timed-out? (= -1 (:exit initial-result))
        result (if timed-out?
                 (do
                   (try (.destroyForcibly process) (catch Exception _ nil))
                   (wait-for-stream-process process (post-kill-join-timeout-ms)))
                 initial-result)]
    (doseq [thread [stdin-thread stdout-thread stderr-thread]
            :when thread]
      (.join ^Thread thread stream-reader-join-timeout-ms))
    {:out (or @stdout "")
     :err (or (not-empty (:err result)) @stderr "")
     :exit (:exit result)}))

(def ^:private keepalive-min-interval-ms
  "Floor on the keepalive interval so the worker doesn't burn cycles
   pinging the monitor in a tight loop on production-sized thresholds.
   1s is plenty fine-grained for a 180+ second stagnation window."
  1000)

(defn- keepalive-interval-ms
  "Refresh interval for the stream-side keepalive thread.

   Tied to the configured stagnation threshold so the keepalive fires
   well within the window — at one-third of the threshold we get
   three refreshes per stagnation-window, so a brief reader stall
   never races the timer.

   Two clamps:
   - lower: `keepalive-min-interval-ms` to avoid a hot-loop on
     production stagnation values.
   - upper: strictly less than the stagnation threshold so the
     keepalive always fires before stagnation can. The min wins
     when the configured threshold is small (e.g. tests using
     80ms) so the lower-bound floor doesn't push the interval
     past the threshold."
  [monitor]
  (let [stagnation (get @monitor :stagnation-threshold-ms
                        (default-stagnation-threshold-ms))
        target     (long (/ stagnation 3))
        ceiling    (max 1 (dec stagnation))]
    (min ceiling (max keepalive-min-interval-ms target))))

(defn- network-drop-timeout
  "Shape the network-monitor's `:on-drop` diagnostic into the same
   `{:type :message :elapsed-ms ...}` envelope `pm/check-timeout`
   returns, so the existing `timeout-result` path handles it
   identically. The PR-C auto-resumer keys on `:type :network-drop` to
   know it should resume from the last persisted checkpoint."
  [{:keys [backend-key consecutive-failures probe-interval-ms]
    :as   diag}]
  {:type           :network-drop
   :backend-key    backend-key
   :elapsed-ms     (* consecutive-failures probe-interval-ms)
   :message        (format "Network drop: %d consecutive probes failed for %s"
                           consecutive-failures (name backend-key))
   :stats          (select-keys diag
                                [:consecutive-failures :failure-threshold
                                 :probe-interval-ms])})

(defn stream-exec-fn
  "Run `cmd` as a streaming subprocess, dispatching each output line to
   `on-line`.

   Options:
   - `:progress-monitor`  — caller-supplied progress monitor; defaults
                            to a fresh `default-progress-monitor`.
   - `:workdir`, `:stdin` — passed through to `start-stream-process!`.
   - `:backend-key`       — when set, starts the network-drop monitor
                            against the corresponding provider endpoint
                            (PR-B). Disabled when omitted so legacy
                            callers / synthetic-stream tests are
                            unaffected.
   - `:network-monitor-opts` — overrides for `start-network-monitor!`
                               (probe-interval-ms, failure-threshold,
                               probe-fn). Tests inject `:probe-fn` here
                               to drive the monitor deterministically
                               without real network I/O."
  ([cmd on-line]
   (stream-exec-fn cmd on-line {}))
  ([cmd on-line {:keys [progress-monitor workdir stdin backend-key
                        network-monitor-opts]}]
   (let [monitor (or progress-monitor (default-progress-monitor))
         stream-process (start-stream-process! cmd {:workdir workdir
                                                    :stdin stdin})
         process (:process stream-process)
         out-reader (java.io.BufferedReader.
                     (java.io.InputStreamReader. (.getInputStream process)))
         ;; Decouple stagnation from the CLI's emission cadence.
         ;; claude in --input-format text mode does not emit
         ;; rate_limit_event during the model's think phase, so the
         ;; stream-heartbeat-based stagnation guard has no signal
         ;; during legitimate thinking on heavy prompts (2026-05-07
         ;; stage-3 dogfood: stagnation timeout at 180s with 6
         ;; init-time chunks then nothing for the model's full
         ;; think). Keepalive refreshes the monitor while the JVM-side
         ;; reader is alive; max-total-ms remains the wedge backstop.
         stop-keepalive! (pm/start-keepalive! monitor
                                              (keepalive-interval-ms monitor))
         ;; Network-drop monitor — independent of the keepalive. PR-B
         ;; of the network-resilience stack. Probes the provider every
         ;; `default-probe-interval-ms` (10s) and fires once after
         ;; `default-failure-threshold` (3) consecutive failures.
         ;; on-drop: stamps the network-drop reason into the shared
         ;; atom and forcibly destroys the subprocess so the reader
         ;; loop exits immediately (the agent is hung on socket I/O
         ;; that will never recover without process restart).
         ;; Disabled when no :backend-key is provided so legacy
         ;; callers (tests with synthetic streams, the `:echo` test
         ;; backend) keep working unchanged.
         network-drop-reason (atom nil)
         stop-network-monitor!
         (when backend-key
           ;; Caller opts merged FIRST so the integration-owned
           ;; :backend-key, :probe-url, and :on-drop always win — a
           ;; test fixture that mistakenly carries those keys can't
           ;; clobber the monitor's correctness contract. Test-only
           ;; knobs (:probe-interval-ms, :failure-threshold, :probe-fn)
           ;; still flow through.
           (nnm/start-network-monitor!
             (merge network-monitor-opts
                    {:backend-key backend-key
                     :probe-url   (probe-endpoint-for backend-key)
                     :on-drop     (fn [diag]
                                    (compare-and-set! network-drop-reason nil
                                                      (network-drop-timeout diag))
                                    ;; Kill the subprocess so the
                                    ;; consumer's reader sees EOF and
                                    ;; the loop exits.
                                    ;; .destroyForcibly is the same
                                    ;; call stop-stream-process! makes;
                                    ;; safe to double-tap.
                                    (try (.destroyForcibly process)
                                         (catch Exception _ nil)))})))
         {:keys [lines timeout anomaly]}
         (try
           (process-stream-lines out-reader monitor on-line)
           (finally
             (stop-keepalive!)
             (when stop-network-monitor! (stop-network-monitor!))))
         ;; A network-drop signal (if any) supersedes whatever
         ;; pm/check-timeout returned — the subprocess was killed by
         ;; the network monitor, so `process-stream-lines` likely
         ;; observed a stream-idle as a knock-on effect. Surface the
         ;; root cause for the workflow / PR-C auto-resumer.
         effective-timeout (or @network-drop-reason timeout)
         ;; Stream ended with a timeout reason (stream-idle,
         ;; stagnation, total-max, or network-drop) — the subprocess
         ;; is hung or silent. Kill it so `deref` returns immediately
         ;; instead of waiting 10 min for a process that will not exit.
         terminal-failure (or effective-timeout anomaly)
         join-timeout (if terminal-failure
                        (post-kill-join-timeout-ms)
                        (process-join-timeout-ms))
         result (stop-stream-process! stream-process
                                      (boolean terminal-failure)
                                      join-timeout)]
     (cond
       effective-timeout
       (timeout-result lines effective-timeout)

       anomaly
       (stream-anomaly-result lines anomaly)

       :else
       (success-result lines result)))))

;------------------------------------------------------------------------------ Layer 2

(defn build-request-prompt [request]
  (or (:prompt request)
      (build-messages-prompt (:messages request))))

(defn- resolve-prompt-via
  "Read the backend's declared prompt-delivery mode. Defaults to :argv
   so backends that pre-date the :prompt-via knob keep their legacy
   behavior."
  [backend-config]
  (get backend-config :prompt-via :argv))

(defn- exec-opts-for-prompt-via
  "Build the exec-layer opts map for a given prompt-via + prompt.

   :stdin → {:stdin prompt} so the exec layer pipes the prompt to the
            subprocess's stdin.
   :argv  → {} (prompt rides in argv via the args-fn).

   Returning an empty map for :argv lets call sites use `(seq opts)`
   to decide between the 1-arity and 2-arity exec-fn calls — preserves
   back-compat with caller-supplied 1-arity exec-fns documented as a
   public test hook on CLIClient."
  [prompt-via prompt]
  (cond-> {} (= prompt-via :stdin) (assoc :stdin prompt)))

(defn- run-cli-exec
  "Invoke a CLI exec-fn with cmd plus optional opts. The 1-arity
   branch keeps caller-supplied exec-fns from the days before opts
   existed working unchanged; the 2-arity branch threads opts in
   when they carry signal (e.g. :stdin)."
  [exec-fn cmd opts]
  (if (seq opts)
    (exec-fn cmd opts)
    (exec-fn cmd)))

(defn normalize-exec-fn
  "Wrap an exec-fn so it accepts both 1-arity (cmd) and 2-arity
   (cmd opts) call patterns.

   `:exec-fn` on `create-client` is a documented public test hook.
   Pre-:prompt-via callers supplied 1-arity functions; once the
   :stdin path landed the impl started invoking exec-fn 2-arity for
   the :claude backend. Without this wrapper a 1-arity user fn
   would throw ArityException the first time the new code path
   ran.

   The wrapper probes 2-arity on first 2-arity call and caches the
   decision in an atom so subsequent calls dispatch directly. The
   probe only happens when the caller actually passes opts; pure
   1-arity invocations stay one indirection.

   Returns a fn equivalent to `f` for callers that already accept
   both arities."
  [f]
  (let [arity (atom :unknown)]
    (fn
      ([cmd] (f cmd))
      ([cmd opts]
       (case @arity
         :one (f cmd)
         :two (f cmd opts)
         (try
           (let [r (f cmd opts)]
             (reset! arity :two)
             r)
           (catch clojure.lang.ArityException _
             (reset! arity :one)
             (f cmd))))))))

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
        {:keys [cmd args-fn]} backend-config]
    (log-prompt-sent logger backend (build-request-prompt request))

    ;; Handle HTTP backends differently from CLI backends
    (if (= cmd "http")
      (let [response (http-complete backend-config request)]
        (log-response logger response)
        response)

      ;; CLI backend
      (let [prompt     (build-request-prompt request)
            prompt-via (resolve-prompt-via backend-config)
            request-with-model (cond-> request model (assoc :model model))
            args     (args-fn (assoc request-with-model
                                     :prompt prompt
                                     :prompt-via prompt-via))
            full-cmd (into [cmd] args)
            opts     (cond-> (exec-opts-for-prompt-via prompt-via prompt)
                       (:workdir request) (assoc :workdir (:workdir request)))
            result   (run-cli-exec exec-fn full-cmd opts)
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

(def context-overflow-error-type
  "Error :type for a prompt that exceeded the model's context window.
   Terminal: excluded from submission-recovery (the turn has no artifact to
   recover and a retry just re-sends the over-budget prompt). See N12 §4."
  "context_overflow")

(defn- usage-token-count
  "Return a numeric usage token count, treating missing or malformed values
   as zero so context accounting remains numeric for upstream payloads."
  [usage token-key]
  (let [tokens (get usage token-key)]
    (if (number? tokens) tokens 0)))

(defn total-input-tokens
  "prompt + cache-creation + cache-read tokens. Summed because a large
   prompt lands mostly under cache-creation, not :input-tokens. See N12 §2."
  [usage]
  (+ (usage-token-count usage :input-tokens)
     (usage-token-count usage :cache-creation-input-tokens)
     (usage-token-count usage :cache-read-input-tokens)))

(defn context-overflow-by-usage?
  "True when total input tokens >= the model's context window — the
   structured, locale/backend-independent overflow signal (N12 §4).
   `context-window` is nil for uncatalogued models → no assertion."
  [usage context-window]
  (boolean (and context-window
                (pos? (total-input-tokens usage))
                (>= (total-input-tokens usage) context-window))))

(defn streaming-error-response
  "Build a streaming error response with diagnostic metadata.

   Diagnostic fields added to the response map (alongside :success/:error/:anomaly):
   - :stop-reason          — last observed stop reason before failure
   - :num-turns            — number of conversation turns consumed
   - :tool-call-count      — total number of tool invocations during the run
   - :final-message-preview — last 500 chars of accumulated content (post-mortem aid)

   `usage` + `context-window` drive context-overflow classification (N12 §4);
   optional — the 9-arity form skips it (for callers without them)."
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
                      (context-overflow-by-usage? usage context-window) context-overflow-error-type
                      :else "cli_error")]
     (cond-> (llm-error category error-type (str/trim error-message)
                        {:exit-code exit-code
                         :stderr err-result
                         :stdout content
                         :raw-stdout raw-stdout
                         :timeout timeout-info})
       stop-reason                 (assoc :stop-reason stop-reason)
       num-turns                   (assoc :num-turns num-turns)
       (some? tool-call-count)     (assoc :tool-call-count tool-call-count)
       (seq final-message-preview) (assoc :final-message-preview final-message-preview)))))

(def ^:private chars-per-token-estimate
  "Rough characters-per-token divisor for a pre-flight input-size estimate.
   ~4 chars/token is the usual English heuristic; this is a coarse gauge of
   headroom against the model's context window, not a billing figure."
  4)

(defn prompt-size-telemetry
  "Pre-flight size gauge over the assembled system + user prompt, computed
   BEFORE dispatch so it survives a context-overflow rejection (where
   `:usage` never arrives). `:estimated-input-tokens` is a coarse chars/4
   estimate, rounded UP so a near-boundary prompt isn't under-reported.
   See N12 §3."
  [system prompt]
  (let [system-chars (count (or system ""))
        user-chars   (count (or prompt ""))
        total-chars  (+ system-chars user-chars)]
    {:system-chars system-chars
     :user-chars user-chars
     :total-chars total-chars
     ;; ceil division (stay conservative for a headroom gauge)
     :estimated-input-tokens (quot (+ total-chars (dec chars-per-token-estimate))
                                   chars-per-token-estimate)}))

(defn handle-streaming [client request on-chunk backend-config progress-monitor]
  (let [{:keys [logger config]} client
        stream-fn (or (:stream-exec-fn client) stream-exec-fn)
        {:keys [backend model]} config
        {:keys [cmd args-fn stream-parser]} backend-config
        prompt     (build-request-prompt request)
        prompt-via (resolve-prompt-via backend-config)
        request-with-model (cond-> request model (assoc :model model))
        args     (args-fn (assoc request-with-model
                                 :prompt prompt
                                 :streaming? true
                                 :prompt-via prompt-via))
        full-cmd (into [cmd] args)
        accumulated-content (atom "")
        accumulated-usage (atom nil)
        accumulated-cost (atom nil)
        accumulated-tools (atom [])
        accumulated-session-id (atom nil)
        accumulated-stop-reason (atom nil)
        accumulated-turns (atom nil)]
    (when logger
      (log/info logger :system :agent/streaming-prompt-sent
                {:data (assoc (prompt-size-telemetry (:system request) prompt)
                              :backend backend
                              :model model)}))
    (let [base-opts {:progress-monitor progress-monitor
                     :workdir (:workdir request)}
          stream-opts (merge base-opts
                             (exec-opts-for-prompt-via prompt-via prompt))
          result (stream-fn
                  full-cmd
                  (stream-with-parser stream-parser on-chunk progress-monitor
                                      accumulated-content accumulated-usage
                                      accumulated-cost accumulated-tools
                                      accumulated-session-id
                                      accumulated-stop-reason accumulated-turns)
                  stream-opts)
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
          fallback-response (when (blank-streaming-success? exit-code
                                                            final-content
                                                            tool-call-count
                                                            usage)
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
                              :reason :empty-stream-success
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
            ;; Cost fallback: some backends (e.g. older Claude Code
            ;; CLI versions, codex stream parser) don't surface
            ;; total_cost_usd in the result frame, so
            ;; @accumulated-cost stays nil and the runner banner
            ;; reports $0.0000 despite real backend costs. When
            ;; cost is nil but usage carries tokens, compute the
            ;; fallback estimate from the model + usage via
            ;; cost/estimate-cost. Models without pricing in the
            ;; table return 0.0 (no false-positive cost), so this
            ;; is a non-decreasing fix — never produces a wrong-
            ;; direction value.
            (let [resolved-cost (or @accumulated-cost
                                    (cost/estimate-cost usage model))]
              (cond-> (streaming-success-response final-content exit-code
                                                  usage resolved-cost
                                                  stop-reason num-turns
                                                  tool-call-count final-message-preview
                                                  (:err result))
                (seq tools) (assoc :tools-called tools)
                session-id  (assoc :session-id session-id)))
            (cond-> (streaming-error-response final-content exit-code (:err result)
                                              diagnostic-content
                                              timeout-info stop-reason num-turns
                                              tool-call-count final-message-preview
                                              usage
                                              ;; effective model actually invoked (honors per-request :model)
                                              (model-registry/context-window-for-model-id
                                               (:model request-with-model)))
              session-id (assoc :session-id session-id))))))))

(defn complete-stream-impl [client request on-chunk]
  (let [{:keys [config]} client
        {:keys [backend]} config
        backend-config (get backends backend)
        {:keys [streaming? cmd]} backend-config
        progress-monitor (or (:progress-monitor request)
                             (pm/create-progress-monitor
                              {:stagnation-threshold-ms (default-stagnation-threshold-ms)
                               :max-total-ms (default-max-total-ms)}))]

    ;; Handle HTTP backends
    (if (= cmd "http")
      (http-stream-complete backend-config request on-chunk)

      ;; CLI backends
      (if-not streaming?
        (handle-non-streaming-fallback client request on-chunk)
        (handle-streaming client request on-chunk backend-config progress-monitor)))))

(defn get-config-impl [client]
  (:config client))

;------------------------------------------------------------------------------ Layer 3

(defn default-exec-fn
  "Run `cmd` with ProcessBuilder, capturing :out / :err / :exit.

   2-arity opts map supports:
   - `:stdin`  — string piped to the subprocess's stdin
   - `:workdir` — directory where the subprocess runs"
  ([cmd] (default-exec-fn cmd {}))
  ([cmd {:keys [stdin workdir]}]
   (let [timeout-ms 600000
         process (start-capture-process! cmd {:stdin stdin
                                              :workdir workdir})]
     (stop-capture-process! process timeout-ms))))

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
    (let [result (execute-fn executor env-id cmd {:workdir workdir})]
      (if (and (map? result) (:data result))
        {:out (get-in result [:data :stdout] "")
         :err (get-in result [:data :stderr] "")
         :exit (get-in result [:data :exit-code] 0)}
        {:out ""
         :err (str "Capsule exec error: " result)
         :exit 1}))))

(defn mock-exec-fn [output & {:keys [exit] :or {exit 0}}]
  (fn
    ([_cmd]      {:out output :err "" :exit exit})
    ([_cmd _opts] {:out output :err "" :exit exit})))

(defn mock-exec-fn-multi [outputs]
  (let [call-count (atom 0)
        respond    (fn []
                     (let [idx @call-count
                           output (get outputs idx (last outputs))]
                       (swap! call-count inc)
                       {:out output :err "" :exit 0}))]
    (fn
      ([_cmd]       (respond))
      ([_cmd _opts] (respond)))))
