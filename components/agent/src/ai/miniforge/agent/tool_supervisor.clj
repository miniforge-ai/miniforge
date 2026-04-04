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

(ns ai.miniforge.agent.tool-supervisor
  "Uniform tool-use supervision for inner LLM agents.

   Evaluates tool-use requests against policy. This is the control-plane
   bridge: vendor-specific mechanisms (Claude hooks, Codex sandbox, Cursor
   flags) call into this uniform evaluation function.

   Architecture:
   - evaluate-tool-use: core policy function (pure, no side effects)
   - meta-evaluate:     semantic LLM-powered quality/relevance check
   - hook-eval-stdin!:  CLI entry point — reads Claude PreToolUse JSON from
                        stdin, evaluates, writes decision to stdout

   Layered evaluation:
   1. Regex guardrails (fast, cheap) — defense-in-depth
   2. Meta-evaluator (LLM call) — quality/relevance judgment
   Container sandbox handles safety; supervision handles quality."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.agent.meta-evaluator :as meta-eval]))

;------------------------------------------------------------------------------ Layer 0
;; Bash command policy

(def dangerous-patterns
  "Regex patterns for commands that should be denied.
   These are destructive or exfiltration-risk commands."
  [#"rm\s+-rf\s+/"
   #"rm\s+-rf\s+~"
   #"rm\s+-rf\s+\*"
   #"mkfs\b"
   #"dd\s+.*of=/dev/"
   #"chmod\s+-R\s+777\s+/"
   #"chown\s+-R\s+.*\s+/"
   #">\s*/dev/sd"
   #"curl\s+.*\|\s*sh"
   #"curl\s+.*\|\s*bash"
   #"wget\s+.*\|\s*sh"
   #"wget\s+.*\|\s*bash"
   #"eval\s+.*\$\("
   #":()\s*\{\s*:\|:\s*&\s*\}\s*;"  ;; fork bomb
   #"shutdown\b"
   #"reboot\b"
   #"init\s+0"
   #"halt\b"
   #"poweroff\b"])

(defn matches-dangerous-pattern?
  "Check if a command matches any dangerous pattern."
  [command]
  (some #(re-find % command) dangerous-patterns))

(defn evaluate-bash-command
  "Evaluate a Bash command against policy.

   Returns {:decision \"allow\"} or {:decision \"deny\" :reason string}."
  [command]
  (cond
    (str/blank? command)
    {:decision "allow"}

    (matches-dangerous-pattern? command)
    {:decision "deny"
     :reason (str "Command matches dangerous pattern: "
                  (first (filter #(re-find % command) dangerous-patterns)))}

    :else
    {:decision "allow"}))

;------------------------------------------------------------------------------ Layer 1
;; Core evaluation function

(defn evaluate-tool-use
  "Evaluate a tool use request against policy.

   Returns {:decision \"allow\"} or {:decision \"deny\" :reason string}.

   This is the uniform supervision function — vendor-specific mechanisms
   (hooks, sandbox, flags) call into this. Each vendor maps the result
   to its own response format."
  [tool-name tool-input]
  (case tool-name
    ;; Read-only tools: always allow
    ("Read" "Glob" "Grep" "WebSearch" "WebFetch" "LS")
    {:decision "allow"}

    ;; MCP artifact tools: always allow
    ("mcp__artifact__submit_code_artifact"
     "mcp__artifact__submit_plan"
     "mcp__artifact__submit_test_artifact"
     "mcp__artifact__submit_release_artifact"
     "mcp__artifact__context_read"
     "mcp__artifact__context_grep"
     "mcp__artifact__context_glob")
    {:decision "allow"}

    ;; Write tools: allow (inner agent needs to generate code)
    ("Edit" "Write" "NotebookEdit")
    {:decision "allow"}

    ;; Bash: evaluate command against policy
    "Bash"
    (evaluate-bash-command (:command tool-input))

    ;; Default: allow (permissive — only deny known-dangerous)
    {:decision "allow"}))

;------------------------------------------------------------------------------ Layer 1.5
;; Semantic meta-evaluation (LLM-powered quality/relevance check)

(defn trivially-safe-tool?
  "Tools that are always on-task and never need semantic review."
  [tool-name]
  (contains? #{"Read" "Glob" "Grep" "WebSearch" "WebFetch" "LS"
               "mcp__artifact__submit_code_artifact"
               "mcp__artifact__submit_plan"
               "mcp__artifact__submit_test_artifact"
               "mcp__artifact__submit_release_artifact"
               "mcp__artifact__context_read"
               "mcp__artifact__context_grep"
               "mcp__artifact__context_glob"}
             tool-name))

(defn evaluate-with-meta
  "Two-layer evaluation: regex guardrails first, then semantic LLM check.

   Arguments:
     tool-name  - Tool name string
     tool-input - Tool input map (keyword keys)
     opts       - Map with :llm-client, :task-context, :phase

   Returns {:decision \"allow\"|\"deny\" :reason string? :meta-eval map?}

   Layer 1 (regex) denials are final. Layer 2 (meta-eval) only runs for
   non-trivial tools that passed Layer 1."
  [tool-name tool-input opts]
  (let [regex-result (evaluate-tool-use tool-name tool-input)]
    (cond
      ;; Layer 1 denied — don't override
      (= "deny" (:decision regex-result))
      regex-result

      ;; Trivially safe tools — skip LLM call
      (trivially-safe-tool? tool-name)
      regex-result

      ;; No LLM client — regex only
      (nil? (:llm-client opts))
      regex-result

      ;; Layer 2: meta-evaluator LLM call
      :else
      (let [eval-result (meta-eval/evaluate
                         {:tool-name tool-name
                          :tool-input tool-input
                          :task-context (:task-context opts)
                          :phase (:phase opts)}
                         {:llm-client (:llm-client opts)})
            decision (:decision eval-result)]
        (if (= :deny decision)
          {:decision "deny"
           :reason (:reasoning eval-result)
           :meta-eval eval-result}
          {:decision "allow"
           :meta-eval eval-result})))))

;------------------------------------------------------------------------------ Layer 1.7
;; Event emission (soft dependency via requiring-resolve)

(defn emit-tool-use-event!
  "Emit a tool-use-evaluated event to the event stream (if available).

   Uses requiring-resolve to avoid hard dependency on event-stream component.
   Silently no-ops if event-stream is not loaded or no stream is bound."
  [tool-name result & [{:keys [event-stream workflow-id phase]}]]
  (try
    (when (and event-stream workflow-id)
      (let [emit-fn (requiring-resolve 'ai.miniforge.event-stream.core/tool-use-evaluated)
            publish-fn (requiring-resolve 'ai.miniforge.event-stream.core/publish!)
            meta-eval (:meta-eval result)
            event (emit-fn event-stream workflow-id tool-name
                           (:decision result)
                           (cond-> {}
                             (:reasoning meta-eval) (assoc :reasoning (:reasoning meta-eval))
                             (:meta-eval? meta-eval) (assoc :meta-eval? true)
                             (:confidence meta-eval) (assoc :confidence (:confidence meta-eval))
                             phase (assoc :phase phase)))]
        (publish-fn event-stream event)))
    (catch Exception _e
      ;; Silently ignore — event emission is observability, not critical path
      nil)))

;------------------------------------------------------------------------------ Layer 2
;; Claude hook protocol (stdin/stdout JSON)

(defn hook-eval-stdin!
  "CLI entry point for `miniforge hook-eval`.

   Reads Claude PreToolUse JSON from stdin:
     {\"tool_name\": \"Bash\", \"tool_input\": {\"command\": \"ls\"}}

   Optionally includes meta-eval context:
     {\"tool_name\": \"Bash\", \"tool_input\": {...},
      \"meta_eval\": {\"task_context\": \"...\", \"phase\": \"implement\"}}

   Writes Claude hook response to stdout:
     {} for allow (empty object = no objection)
     {\"decision\": \"block\", \"reason\": \"...\"} for deny

   Returns exit code 0 always (non-zero would crash the hook)."
  []
  (try
    (let [input (json/parse-string (slurp *in*) true)
          tool-name (:tool_name input)
          tool-input (get input :tool_input {})
          tool-input-kw (into {} (map (fn [[k v]] [(keyword k) v]) tool-input))
          meta-eval-config (:meta_eval input)
          result (if meta-eval-config
                   ;; Meta-eval enabled: two-layer evaluation
                   (let [llm-client (when-let [backend (keyword (get meta-eval-config :backend "claude"))]
                                      (try
                                        (let [create-client (requiring-resolve
                                                             'ai.miniforge.llm.protocols.records.llm-client/create-client)]
                                          (create-client {:backend backend}))
                                        (catch Exception _e nil)))]
                     (evaluate-with-meta tool-name tool-input-kw
                                         {:llm-client llm-client
                                          :task-context (:task_context meta-eval-config)
                                          :phase (some-> (:phase meta-eval-config) keyword)}))
                   ;; No meta-eval: regex only
                   (evaluate-tool-use tool-name tool-input-kw))
          ;; Emit event (no-ops if no event stream bound)
          _ (emit-tool-use-event! tool-name result)]
      (if (= "deny" (:decision result))
        (println (json/generate-string {:decision "block"
                                        :reason (:reason result)}))
        ;; Empty object = allow (no objection from hook)
        (println (json/generate-string {}))))
    (catch Exception e
      ;; On error, allow (fail-open) — don't block the inner agent
      (binding [*out* *err*]
        (println (str "WARN: hook-eval error: " (ex-message e))))
      (println (json/generate-string {}))))
  0)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test evaluate-tool-use
  (evaluate-tool-use "Read" {:file_path "/tmp/x"})
  ;; => {:decision "allow"}

  (evaluate-tool-use "Bash" {:command "ls -la"})
  ;; => {:decision "allow"}

  (evaluate-tool-use "Bash" {:command "rm -rf /"})
  ;; => {:decision "deny" :reason "..."}

  (evaluate-tool-use "mcp__artifact__submit_code_artifact" {})
  ;; => {:decision "allow"}

  :end)
