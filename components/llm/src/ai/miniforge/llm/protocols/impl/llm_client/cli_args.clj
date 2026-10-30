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
(ns ai.miniforge.llm.protocols.impl.llm-client.cli-args
  "Argument builders for the agent CLI backends (Claude, Codex, Cursor,
   OpenCode, echo)."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.llm.protocols.impl.llm-client.settings :as settings]
   [ai.miniforge.response.interface :as response]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Backend configurations
;; Named argument builders — extracted from the backends config map for clarity.
(defn ^{:stratum 0} claude-mcp-allowlist-string
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

(def ^{:stratum 0} ^:private claude-isolation-args
  "Flags that keep a call off the operator's Claude Code setup: no
   built-in tools, no user/project/local settings (so no hooks and no
   plugins), and no skills or slash commands. Without them a single-turn
   completion loads the operator's hooks and plugins: a SessionStart hook
   that tells the model to load a skill ends a one-turn call on a tool
   call with no answer, and the setup adds ~36K tokens of context to every
   call. Paired with --strict-mcp-config, added in `claude-args` when no
   --mcp-config is given."
  ["--tools" "" "--setting-sources" "" "--disable-slash-commands"])

(def ^{:stratum 0} ^:private claude-input-format-flag
  "Claude CLI flag that selects how the user prompt is delivered. Paired
   with `claude-input-format-stdin` (the only value we use today), this
   tells the CLI to read the prompt from stdin instead of argv."
  "--input-format")

(def ^{:stratum 0} ^:private claude-input-format-stdin
  "Argument value for `claude-input-format-flag` that switches the CLI
   to text-on-stdin input mode."
  "text")

(defn ^{:stratum 0} codex-args
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

(defn ^{:stratum 0} cursor-args
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

(def ^{:stratum 0} ^:private isolation-agent
  "The OpenCode agent an isolated call selects. Its permissions come from
   the call's inline config, and OpenCode applies an agent's rules after
   the global and project ones, so its final \"*\" deny wins over any
   allow a repository's opencode.json adds (including grants on its own
   agents). The name is random per process, so a repository cannot define
   an agent of the same name to merge its own rules into this one."
  (delay (str "miniforge-isolated-" (subs (str (java.util.UUID/randomUUID)) 0 8))))

(defn ^{:stratum 0} operator-opencode-config
  "The OPENCODE_CONFIG_CONTENT the operator set, parsed, so an isolated
   call keeps its provider, model and endpoint settings; {} when unset.
   Throws an :anomalies/incorrect anomaly when it is set but is not a
   plain JSON object: OpenCode also reads JSONC, which miniforge cannot
   rewrite without dropping the operator's settings."
  []
  (let [raw (settings/getenv-value "OPENCODE_CONFIG_CONTENT")]
    (if (str/blank? raw)
      {}
      (let [parsed (try+ (json/parse-string raw) (catch Object _ nil))]
        (if (map? parsed)
          parsed
          (response/throw-anomaly! :anomalies/incorrect
                                   "OPENCODE_CONFIG_CONTENT must be a plain JSON object for miniforge to set an OpenCode call's permissions"
                                   {}))))))

(defn ^{:stratum 0} echo-args
  "Build CLI arguments for the echo (test) backend."
  [{:keys [prompt]}]
  [prompt])

(defn- ^{:stratum 0} default-claude-cli-budget-usd
  []
  (settings/client-default [:claude-cli :default-budget-usd]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} opencode-args
  "Build CLI arguments for the OpenCode backend.

   OpenCode owns provider credentials, model aliases, project config,
   agent profiles, MCP config, and permissions. Miniforge passes the
   selected model/profile knobs through and treats OpenCode as the
   common provider wrapper instead of handling API keys directly.

   A call outside an artifact session, or one that asks to be
   `:isolated?`, runs `--pure` (no external plugins) as `isolation-agent`,
   whose permissions `opencode-env` denies; the request's own `:agent` is
   not used. Such a call may not `:attach` to a running server (its own
   config decides its tools), run a configured `:command` (one can expand
   shell expressions and name its own agent), or resume a session with
   `:session`, `:continue?` or `:fork?` (a session's saved permission
   rules apply after the agent's)."
  [{:keys [prompt model agent attach command session continue? fork? files format
           supervision isolated?] :as request}]
  (let [isolate? (or isolated? (not (:settings-path supervision)))]
    (when-let [refused (and isolate? (seq (filter #(get request %) [:attach :command :session :continue? :fork?])))]
      (response/throw-anomaly! :anomalies/incorrect
                               "An isolated OpenCode call cannot attach, run a configured command, or resume a session"
                               {:refused (vec refused)}))
    (cond-> ["run"]
      isolate?                  (into ["--pure" "--agent" @isolation-agent])
      attach                    (into ["--attach" attach])
      command                   (into ["--command" command])
      continue?                 (conj "--continue")
      session                   (into ["--session" session])
      fork?                     (conj "--fork")
      model                     (into ["--model" model])
      (and agent (not isolate?)) (into ["--agent" agent])
      (seq files)               (into (mapcat (fn [file] ["--file" file]) files))
      format                    (into ["--format" format])
      true                      (conj prompt))))

(defn ^{:stratum 1} opencode-env
  "Environment entries for an OpenCode call. A call outside an artifact
   session (no hook settings under `:supervision`), or one that asks to be
   `:isolated?`, gets OPENCODE_PURE=1, OPENCODE_DISABLE_PROJECT_CONFIG=1
   and OPENCODE_CONFIG_CONTENT: the operator's own inline config
   (`operator-opencode-config`) with every permission denied
   (`[:opencode :deny-all-permission]` in llm/client-defaults.edn),
   globally and on `isolation-agent`, which the call selects. Project
   config is off because the worktree is content an agent can write: an
   opencode.json or .opencode/agent/<name>.md there would merge into the
   agent once its name is known (it shows in the process list), and a
   project MCP server would start even under --pure. nil otherwise."
  [{:keys [supervision isolated?]}]
  (when (or isolated? (not (:settings-path supervision)))
    (let [deny  (settings/client-default [:opencode :deny-all-permission])
          agent {"mode" "primary"
                 "description" "miniforge: a call with no tools"
                 "permission" deny}]
      {"OPENCODE_PURE" "1"
       "OPENCODE_DISABLE_PROJECT_CONFIG" "1"
       "OPENCODE_CONFIG_CONTENT"
       (json/generate-string
        (-> (operator-opencode-config)
            (assoc "permission" deny)
            (update "agent" #(assoc (if (map? %) % {}) @isolation-agent agent))))})))

(defn ^{:stratum 1} claude-args
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
   string into the process via the exec layer's `:stdin` option.

   A call runs isolated (`claude-isolation-args`) when the request sets
   `:isolated?` or carries no hook settings (no `:settings-path` under
   `:supervision`). Only an artifact session writes those settings, so a
   call outside one has no PreToolUse hook: it gets no built-in tools and
   none of the operator's settings, hooks, plugins or skills, rather than
   whatever tools the operator's own settings would allow it."
  [{:keys [prompt system max-tokens streaming? mcp-config mcp-allowed-tools
           disallowed-tools supervision budget-usd max-turns model resume
           prompt-via isolated?]
    :or   {prompt-via :argv}}]
  (let [budget (or budget-usd
                   (when max-tokens (default-claude-cli-budget-usd)))
        stdin? (= prompt-via :stdin)
        isolate? (or isolated? (not (:settings-path supervision)))]
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
      isolate?                     (into claude-isolation-args)
      (and isolate? (not mcp-config)) (conj "--strict-mcp-config")
      (seq mcp-allowed-tools)      (into ["--allowedTools" (claude-mcp-allowlist-string mcp-allowed-tools)])
      (seq disallowed-tools)       (into ["--disallowedTools" (str/join "," disallowed-tools)])
      (:settings-path supervision) (into ["--settings" (:settings-path supervision)])
      system                       (into ["--system-prompt" system])
      budget                       (into ["--max-budget-usd" (str budget)])
      max-turns                    (into ["--max-turns" (str max-turns)])
      model                        (into ["--model" model])
      resume                       (into ["--resume" resume])
      (not stdin?)                 (conj prompt))))
