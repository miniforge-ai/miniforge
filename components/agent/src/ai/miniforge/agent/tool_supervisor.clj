(ns ai.miniforge.agent.tool-supervisor
  "Uniform tool-use supervision for inner LLM agents.

   Evaluates tool-use requests against policy. This is the control-plane
   bridge: vendor-specific mechanisms (Claude hooks, Codex sandbox, Cursor
   flags) call into this uniform evaluation function.

   Architecture:
   - evaluate-tool-use: core policy function (pure, no side effects)
   - hook-eval-stdin!:  CLI entry point — reads Claude PreToolUse JSON from
                        stdin, evaluates, writes decision to stdout"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Bash command policy

(def ^:private dangerous-patterns
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

(defn- matches-dangerous-pattern?
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
     "mcp__artifact__submit_release_artifact")
    {:decision "allow"}

    ;; Write tools: allow (inner agent needs to generate code)
    ("Edit" "Write" "NotebookEdit")
    {:decision "allow"}

    ;; Bash: evaluate command against policy
    "Bash"
    (evaluate-bash-command (:command tool-input))

    ;; Default: allow (permissive — only deny known-dangerous)
    {:decision "allow"}))

;------------------------------------------------------------------------------ Layer 2
;; Claude hook protocol (stdin/stdout JSON)

(defn hook-eval-stdin!
  "CLI entry point for `miniforge hook-eval`.

   Reads Claude PreToolUse JSON from stdin:
     {\"tool_name\": \"Bash\", \"tool_input\": {\"command\": \"ls\"}}

   Writes Claude hook response to stdout:
     {} for allow (empty object = no objection)
     {\"decision\": \"block\", \"reason\": \"...\"} for deny

   Returns exit code 0 always (non-zero would crash the hook)."
  []
  (try
    (let [input (json/parse-string (slurp *in*) true)
          tool-name (:tool_name input)
          tool-input (or (:tool_input input) {})
          ;; Convert string keys to keyword keys for evaluate-tool-use
          tool-input-kw (into {} (map (fn [[k v]] [(keyword k) v]) tool-input))
          result (evaluate-tool-use tool-name tool-input-kw)]
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
