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

   Evaluates tool-use requests against policy. Today only Claude sessions
   reach it, through the PreToolUse hook; Codex and Cursor sessions get
   their own CLI permission configs (artifact-session) and no role policy.

   Default-deny: a role may call only the tools config/agent/tool-policy.edn
   lists for it. Any other tool is refused, every tool is refused when the
   role is missing or unlisted, and an error while evaluating blocks the
   call.

   Architecture:
   - evaluate-tool-use: core policy function (pure given a policy; the
                        three-argument form reads tool-policy once)
   - hook-eval-stdin!:  CLI entry point — reads Claude PreToolUse JSON from
                        stdin, evaluates, writes decision to stdout"
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [ai.miniforge.agent.tool-policy :as tool-policy]
   [ai.miniforge.event-stream.interface :as events]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.io PushbackReader]))

;------------------------------------------------------------------------------ Layer 0

;; Policy data
(def ^{:stratum 0} tool-policy
  "The tool policy, read from the classpath resource
   config/agent/tool-policy.edn on first use by
   `tool-policy/read-tool-policy`, which checks it against its schema. A
   missing, unreadable or malformed resource throws there, and the hook
   then refuses the tool call."
  (delay (-> (io/resource "config/agent/tool-policy.edn")
             slurp
             tool-policy/read-tool-policy)))

(defn ^{:stratum 0} role-tools
  "The tools `policy` lets `role` call: its :common set plus the role's
   own. nil when `policy` does not list `role`."
  [policy role]
  (when-let [own (get-in policy [:roles role])]
    (into (get policy :common #{}) own)))

;; Bash command policy
(defn ^{:stratum 0} evaluate-bash-command
  "Evaluate a Bash command against `policy`: refused when longer than its
   :bash/max-command-length (checked first, so a padded command cannot
   slow the patterns down) or when it matches one of :bash/deny-patterns.

   Returns {:decision \"allow\"} or {:decision \"deny\" :reason string}."
  [policy command]
  (let [command (str command)
        limit   (:bash/max-command-length policy)]
    (if (> (count command) limit)
      {:decision "deny"
       :reason (str "Command is longer than " limit " characters; write long content with Write")}
      (if-let [pattern (some #(when (re-find % command) %) (:bash/deny-patterns policy))]
        {:decision "deny"
         :reason (str "Command matches dangerous pattern: " pattern)}
        {:decision "allow"}))))

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
   decision, so Claude's normal permission flow applies: in an artifact
   session that is `--permission-mode dontAsk` with the role's tools in
   the session settings' permissions.allow, so Claude's own checks still
   refuse what they would have asked about), and for deny
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
  "Evaluate a session's request to call `tool-name` with `tool-input`.
   `session` carries the agent :role, the worktree :root and :path-ok?
   (see `tool-policy/path-refusal`).

   Returns {:decision \"allow\"} or {:decision \"deny\" :reason string}.
   Denies a tool `role-tools` does not list for the role, every tool when
   `policy` has no entry for the role (nil included), a path outside the
   worktree or in its git metadata, and a Bash command
   `evaluate-bash-command` refuses. The three-argument form reads
   `tool-policy`.

   `hook-eval-stdin!` maps the result to Claude's PreToolUse reply."
  ([session tool-name tool-input]
   (evaluate-tool-use @tool-policy session tool-name tool-input))
  ([policy {:keys [role] :as session} tool-name tool-input]
   (let [allowed (role-tools policy role)
         outside (when (contains? allowed tool-name)
                   (tool-policy/path-refusal policy session tool-name tool-input))]
     (cond
       (nil? allowed)
       {:decision "deny"
        :reason (str "No tool policy for role " (pr-str role) "; every tool is refused")}

       (not (contains? allowed tool-name))
       {:decision "deny"
        :reason (str "Tool " (pr-str tool-name) " is not allowed for role " (pr-str role))}

       outside
       {:decision "deny" :reason outside}

       (= "Bash" tool-name)
       (evaluate-bash-command policy (:command tool-input))

       :else
       {:decision "allow"}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} hook-eval-stdin!
  "CLI entry point for `miniforge hook-eval --role <role>`.

   Reads Claude PreToolUse JSON from stdin:
     {\"tool_name\": \"Bash\", \"tool_input\": {\"command\": \"ls\"}}
   evaluates it for the session `opts` describe (:role, :root, :path-ok?;
   with no role every tool is refused, with no root every file tool) and
   writes `hook-reply` to stdout.

   Returns the exit code: 0 once a reply is written. When reading or
   evaluating the request throws, it writes nothing to stdout, explains on
   stderr and returns 2: for PreToolUse, Claude reads exit code 2 as a
   blocking error, refuses the tool call and shows the agent stderr. Any
   other non-zero code lets the call through, so the hook command
   artifact-session writes turns a failure to start this one into 2 too."
  ([] (hook-eval-stdin! {}))
  ([opts]
   (try+
    (let [input (read-hook-request)
          tool-name (:tool_name input)
          tool-input (get input :tool_input {})
          tool-input-kw (into {} (map (fn [[k v]] [(keyword k) v]) tool-input))
          result (evaluate-tool-use opts tool-name tool-input-kw)
          ;; Emit event (no-ops if no event stream bound)
          _ (emit-tool-use-event! tool-name result)]
      (println (json/generate-string (hook-reply result)))
      0)
    (catch Object _
      (binding [*out* *err*]
        (println (str "hook-eval refused the tool call: evaluation failed: "
                      (get &throw-context :message "no message"))))
      2))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (evaluate-tool-use {:role :implementer} "Bash" {:command "ls -la"})
  ;; => {:decision "allow"}

  (evaluate-tool-use {:role :implementer} "Bash" {:command "rm -rf /"})
  ;; => {:decision "deny" :reason "..."}

  (evaluate-tool-use {:role :reviewer} "Write" {:file_path "src/x.clj"})
  ;; => {:decision "deny" :reason "Tool \"Write\" is not allowed for role :reviewer"}

  (evaluate-tool-use {} "mcp__context__context_read" {:path "src/core.clj"})
  ;; => {:decision "deny" :reason "No tool policy for role nil; every tool is refused"}

  :end)
