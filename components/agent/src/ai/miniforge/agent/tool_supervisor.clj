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
   - hook-eval-stdin!:  CLI entry point — reads Claude PreToolUse JSON from
                        stdin, evaluates, writes decision to stdout

   The policy data is config/agent/tool-policy.edn."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.event-stream.interface :as events]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.io PushbackReader]))

;------------------------------------------------------------------------------ Layer 0

;; Policy data
(def ^{:stratum 0} tool-policy
  "The tool policy, read from the classpath resource
   config/agent/tool-policy.edn on first use, with its
   :bash/deny-patterns compiled to regexes. A missing or unreadable
   resource throws there, and the hook then refuses the tool call."
  (delay (-> (io/resource "config/agent/tool-policy.edn")
             slurp
             edn/read-string
             (update :bash/deny-patterns #(mapv re-pattern %)))))

;; Bash command policy
(defn ^{:stratum 0} evaluate-bash-command
  "Evaluate a Bash command against `deny-patterns`.

   Returns {:decision \"allow\"} or {:decision \"deny\" :reason string}."
  [deny-patterns command]
  (if-let [pattern (some #(when (re-find % (str command)) %) deny-patterns)]
    {:decision "deny"
     :reason (str "Command matches dangerous pattern: " pattern)}
    {:decision "allow"}))

;; Event emission
(defn ^{:stratum 0} emit-tool-use-event!
  "Emit a tool-use-evaluated event to the event stream (if available).

   Silently no-ops if no event stream or workflow id is bound."
  [tool-name result & [{:keys [event-stream workflow-id phase]}]]
  (try
    (when (and event-stream workflow-id)
      (let [event (events/tool-use-evaluated
                    event-stream workflow-id tool-name
                    (:decision result)
                    (cond-> {}
                      phase (assoc :phase phase)))]
        (events/publish! event-stream event)))
    (catch Exception _e
      ;; Silently ignore — event emission is observability, not critical path
      nil)))

;; Claude hook protocol (stdin/stdout JSON)
(defn- ^{:stratum 0} read-hook-request
  "Parse a single Claude hook JSON payload from stdin without waiting for EOF.

   Claude's PreToolUse hook contract is request/response over a live stream.
   Reading with `slurp` waits for stream closure and can deadlock before the
   first tool approval. `parse-stream` reads one JSON value and returns as soon
   as the object is complete."
  []
  (json/parse-stream (PushbackReader. *in*) true))

(defn ^{:stratum 0} hook-reply
  "The PreToolUse reply for an evaluation `result`: {} for allow (no
   decision, so Claude's normal permission flow applies), and for deny
   hookSpecificOutput with hookEventName PreToolUse, permissionDecision
   deny and the reason. That is the current PreToolUse output
   (https://code.claude.com/docs/en/hooks); the top-level
   {\"decision\": \"block\"} it replaces is deprecated for this event."
  [result]
  (if (= "deny" (:decision result))
    {:hookSpecificOutput {:hookEventName "PreToolUse"
                          :permissionDecision "deny"
                          :permissionDecisionReason (:reason result)}}
    {}))

;------------------------------------------------------------------------------ Layer 1

;; Core evaluation function
(defn ^{:stratum 1} evaluate-tool-use
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

    ;; MCP context tools: always allow
    ("mcp__context__context_read"
     "mcp__context__context_grep"
     "mcp__context__context_glob")
    {:decision "allow"}

    ;; Write tools: allow (inner agent needs to generate code)
    ("Edit" "Write" "NotebookEdit")
    {:decision "allow"}

    ;; Bash: evaluate command against policy
    "Bash"
    (evaluate-bash-command (:bash/deny-patterns @tool-policy) (:command tool-input))

    ;; Default: allow (permissive — only deny known-dangerous)
    {:decision "allow"}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} hook-eval-stdin!
  "CLI entry point for `miniforge hook-eval`.

   Reads Claude PreToolUse JSON from stdin:
     {\"tool_name\": \"Bash\", \"tool_input\": {\"command\": \"ls\"}}
   evaluates it and writes `hook-reply` to stdout.

   Returns the exit code: 0 once a reply is written. When reading or
   evaluating the request throws, it writes nothing to stdout, explains on
   stderr and returns 2: for PreToolUse, Claude reads exit code 2 as a
   blocking error, refuses the tool call and shows the agent stderr. Any
   other non-zero code lets the call through."
  []
  (try+
   (let [input (read-hook-request)
         tool-name (:tool_name input)
         tool-input (get input :tool_input {})
         tool-input-kw (into {} (map (fn [[k v]] [(keyword k) v]) tool-input))
         result (evaluate-tool-use tool-name tool-input-kw)
         ;; Emit event (no-ops if no event stream bound)
         _ (emit-tool-use-event! tool-name result)]
     (println (json/generate-string (hook-reply result)))
     0)
   (catch Object _
     (binding [*out* *err*]
       (println (str "hook-eval refused the tool call: evaluation failed: "
                     (get &throw-context :message "no message"))))
     2)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test evaluate-tool-use
  (evaluate-tool-use "Read" {:file_path "/tmp/x"})
  ;; => {:decision "allow"}

  (evaluate-tool-use "Bash" {:command "ls -la"})
  ;; => {:decision "allow"}

  (evaluate-tool-use "Bash" {:command "rm -rf /"})
  ;; => {:decision "deny" :reason "..."}

  (evaluate-tool-use "mcp__context__context_read" {"path" "src/core.clj"})
  ;; => {:decision "allow"}

  :end)
