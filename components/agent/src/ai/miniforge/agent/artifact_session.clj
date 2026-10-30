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
(ns ai.miniforge.agent.artifact-session
  "MCP artifact session management.

   Creates temporary directories with MCP config for the Claude CLI,
   allowing the inner LLM to submit structured artifacts via MCP tools
   instead of outputting raw EDN text.

   This namespace holds the session lifecycle and the `with-session`
   runners. The agent CLI config writers live in `artifact-session.cli-config`,
   output parsing in `artifact-session.parsing`, and output readers in
   `artifact-session.readers`. Aliases keep the moved definitions callers
   use under their names here, and this namespace calls them through the
   aliases, so redefining `artifact-session/<name>` still reaches it."
  (:require
   [ai.miniforge.agent.artifact-session.cli-config :as cli-config]
   [ai.miniforge.agent.artifact-session.parsing :as parsing]
   [ai.miniforge.agent.artifact-session.readers :as readers]
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.anomaly.interface :as anomaly]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

;; Schema & session lifecycle
(def ^{:stratum 0} Session
  "Schema for an artifact session map."
  [:map
   [:dir [:string {:min 1}]]
   [:mcp-config-path [:string {:min 1}]]
   [:artifact-path [:string {:min 1}]]])

(defn ^{:stratum 0} create-session!
  "Create a new artifact session with a temporary directory.

   Optionally accepts a workdir override (e.g., a git worktree path) for
   the pre-session snapshot. Defaults to the JVM's current working directory.

   Returns:
   - {:dir <path>, :mcp-config-path <path>, :artifact-path <path>}"
  ([] (create-session! nil))
  ([{:keys [workdir source-root]}]
   (let [dir (str (Files/createTempDirectory
                   "miniforge-artifact-"
                   (into-array FileAttribute [])))
         working-dir (or workdir (System/getProperty "user.dir"))
         config-path (str dir "/mcp-config.json")
         artifact-path (str dir "/artifact.edn")]
     {:dir dir
      :workdir working-dir
      ;; Tracks whether the caller explicitly provided a worktree path.
      ;; `run-session` gates the worktree-artifacts scan on this flag to
      ;; prevent the JVM CWD fallback (which may contain stale .miniforge/
      ;; artifacts from prior dogfood runs) from polluting the WARN signal.
      :explicit-workdir? (some? workdir)
      :config-root (or workdir dir)
      :source-root source-root
      :mcp-config-path config-path
      :artifact-path artifact-path
      ;; `snapshot-working-dir` is anomaly-returning post-W7.2 cleanup —
      ;; a git failure now yields an `:anomaly/category :anomalies/fault`
      ;; map rather than throwing. The legacy try/catch only handled
      ;; thrown exceptions (e.g. `Process` invocation faults that escape
      ;; the wrapper); add an explicit anomaly check so a return-as-data
      ;; fault doesn't flow into `:pre-session-snapshot` and break
      ;; downstream diffing / changed-paths logic.
      :pre-session-snapshot (let [snap (try
                                         (file-artifacts/snapshot-working-dir working-dir)
                                         (catch Exception _ nil))]
                              (if (or (nil? snap) (anomaly/anomaly? snap))
                                (file-artifacts/empty-snapshot)
                                snap))})))

(defn ^{:stratum 0} write-context-cache!
  "Write context cache EDN to the session directory.

   The MCP artifact server loads this on startup and uses it as
   a read-through cache for context_read/context_grep/context_glob tools.

   Arguments:
   - session - Session map from create-session!
   - files   - Map of {relative-path content-string}

   Returns: session (for threading)"
  [session files]
  (when (seq files)
    (let [path (str (:dir session) "/context-cache.edn")]
      (spit path (pr-str {:files files}))))
  session)

(defn ^{:stratum 0} write-capsule-context-cache!
  "Write context cache EDN inside the capsule via executor.
   Capsule variant of write-context-cache! for governed mode."
  [session files]
  (when (seq files)
    (let [path    (str (:dir session) "/context-cache.edn")
          content (pr-str {:files files})]
      ((:exec! session) (:executor session) (:environment-id session)
                        (str "cat > " (file-artifacts/shell-quote path) " << 'CACHEEOF'\n" content "\nCACHEEOF")
                        {:workdir (:workdir session)})))
  session)

;; Capsule-aware session lifecycle (N11 §6.3-6.4)
(defn- ^{:stratum 0} missing-execute-fn!
  []
  (throw (ex-info "Capsule artifact sessions require :execution/execute-fn"
                  {:missing :execution/execute-fn
                   :component :agent/artifact-session})))

(defn ^{:stratum 0} cleanup-capsule-session!
  "Remove the session directory inside the capsule.

   Runs with the thread's interrupt flag cleared and sets it again
   afterwards. An interrupted agent call (the implement watchdog's kill)
   unwinds through here with the flag set, and the executor's wait on
   `docker exec` is interruptible, so the removal would otherwise stop
   before it ran."
  [session]
  (let [interrupted? (Thread/interrupted)]
    (try+
      ((:exec! session) (:executor session) (:environment-id session)
                        (str "rm -rf " (file-artifacts/shell-quote (:dir session))) {:workdir (:workdir session)})
      ;; JVM Errors stay fatal. A new interrupt during the cleanup is
      ;; kept visible as the flag, without replacing the session outcome.
      (catch Error fatal (throw fatal))
      (catch InterruptedException _ (.interrupt (Thread/currentThread)))
      ;; `Object`, not `Exception`: try+ matches an ex-info by its data
      ;; map, so a class selector would let it escape and replace the
      ;; outcome of the session being cleaned up.
      (catch Object _ nil)
      (finally
        (when interrupted?
          (.interrupt (Thread/currentThread)))))))

;; Session → MCP opts
(defn ^{:stratum 0} session->mcp-opts
  "Build the base MCP options map from a session for LLM chat calls.
   Callers merge role-specific keys (e.g. :model, :disallowed-tools, :workdir)."
  [session budget-usd max-turns]
  {:mcp-config        (:mcp-config-path session)
   :mcp-allowed-tools (:mcp-allowed-tools session)
   :supervision       (:supervision session)
   :budget-usd        budget-usd
   :max-turns         max-turns})

;; Unified session dispatch (N11 §6.3)
(defn ^{:stratum 0} governed?
  "True when the execution context requires governed (capsule) mode.
   Checks :execution/mode is :governed and executor + environment-id are present."
  [context]
  (and (= :governed (:execution/mode context))
       (some? (:execution/executor context))
       (some? (:execution/environment-id context))))

(defn- ^{:stratum 0} run-session
  "Execute body-fn with session, read artifacts, and clean up.
   Shared lifecycle for both host and capsule sessions.

   `read-outputs-fn` is the mode-specific session-outputs reader
   (`read-host-session-outputs` or `read-capsule-session-outputs`) and
   must return {:artifact :context-misses :context-reads :codex-answers
   :codex-answer-log}. Both modes surface all of them — the capsule
   reader batches them into one executor round-trip rather than
   dropping the context files.

   `:worktree-artifacts` is a map from role keyword to parsed artifact,
   read from <workdir>/.miniforge/<role>.edn. Container-promotion pattern —
   agents write their artifact into the worktree and the runtime picks it
   up here. Empty when no explicit worktree is available or no files were
   written.

   Scans all 5 phase roles (:plan :implement :verify :review :release)
   regardless of the current phase - stale artifacts from previous phases
   will appear in :worktree-artifacts but are ignored by callers that key
   on a specific role.

   The worktree scan and WARN are gated on `:explicit-workdir?` to prevent
   the JVM CWD fallback (which may contain stale .miniforge/ artifacts from
   prior runs) from suppressing or emitting diagnostics when no real
   worktree was provided.

   Emits a WARN (not ERROR) only when BOTH the MCP artifact path and all
   worktree role files are empty — genuine 'nothing found' case. The
   warning names the scanned `.miniforge/` directory and the full list of
   role files probed so post-mortem readers can tell that several roles
   were attempted, not a single path. When the worktree-promoted artifact
   was written successfully, this check passes silently.

   Return map includes `:artifact` (the MCP-submitted artifact EDN, or nil)
   and `:worktree-artifacts` (map of role -> artifact for worktree-promoted
   files). A nil `:artifact` combined with an empty `:worktree-artifacts`
   is the machine-readable signal that no artifact was produced. Callers
   that key on phase output MUST treat this combination as a workflow fault
   - no artifact means the implementing agent did not deliver work product."
  [session body-fn read-outputs-fn cleanup-fn mode]
  (try
    (let [result              (body-fn session)
          workdir             (:workdir session)
          read-role-artifact  (readers/role-reader-for-mode mode session workdir)
          worktree-artifacts  (if (:explicit-workdir? session)
                                (readers/collect-worktree-artifacts read-role-artifact workdir)
                                {})
          {:keys [artifact context-misses context-reads codex-answers
                  codex-answer-log]} (read-outputs-fn session)
          ;; Track whether any .miniforge/<role>.edn files existed on disk,
          ;; independent of parse success. A file that exists but contains
          ;; malformed EDN returns nil from read-role-artifact (and emits
          ;; :warn/worktree-artifact-parse) — that nil is dropped by
          ;; collect-worktree-artifacts, making worktree-artifacts appear
          ;; empty even though the agent DID write a file. Without this flag,
          ;; :warn/no-artifact-found fires on top of the parse WARN.
          ;; Host-mode only: capsule mode defers existence probing to a future
          ;; iteration to avoid extra executor round-trips per role.
          any-file-existed?   (and (:explicit-workdir? session)
                                   (= mode :host)
                                   (readers/any-worktree-files-exist? workdir))]
      ;; Diagnostic INFO: MCP submission channel was skipped but the
      ;; worktree-promotion channel succeeded. Suppresses alarm for the normal
      ;; Write-based submission pattern while still being visible for diagnostics.
      (when (and (:explicit-workdir? session)
                 (nil? artifact)
                 (seq worktree-artifacts))
        (parsing/emit-system-message! :info/mcp-artifact-skipped
                              (or workdir "<no-workdir>")))
      ;; Hard-failure WARN: neither submission channel produced an artifact AND
      ;; no worktree role files were present on disk.
      ;;
      ;; Host mode: `any-file-existed?` suppresses the warn when a file was
      ;; present on disk but failed to parse — :warn/worktree-artifact-parse
      ;; already covers that failure mode and firing both warnings would be
      ;; misleading.
      ;;
      ;; Capsule mode: `any-file-existed?` is always false here (existence
      ;; probing is skipped to avoid an extra executor round-trip per role —
      ;; see the comment above), so a parse-failed worktree file CAN trigger
      ;; both :warn/capsule-worktree-artifact-parse and :warn/no-artifact-found.
      ;; Accepted trade-off for now; future work can either lift existence
      ;; probing into capsule mode or thread parse-failure signals back out
      ;; of `read-capsule-worktree-artifact`.
      (when (and (:explicit-workdir? session)
                 (nil? artifact)
                 (empty? worktree-artifacts)
                 (not any-file-existed?))
        (readers/emit-no-artifact-warning! session workdir))
      {:llm-result           result
       :artifact             artifact
       :worktree-artifacts   worktree-artifacts
       :context-misses       context-misses
       :context-reads        context-reads
       :codex-answers        codex-answers
       :codex-answer-log     codex-answer-log
       :pre-session-snapshot (:pre-session-snapshot session)
       :session-mode         mode})
    (finally
      (cleanup-fn session))))

;; Aliases: the moved definitions keep their artifact-session names for callers.
(def ^{:stratum 0} capsule-answers-present-marker
  "Alias of `parsing/capsule-answers-present-marker`."
  parsing/capsule-answers-present-marker)

(def ^{:stratum 0} capsule-output-boundary
  "Alias of `parsing/capsule-output-boundary`."
  parsing/capsule-output-boundary)

(def ^{:stratum 0} backup-cursor-permissions!
  "Alias of `cli-config/backup-cursor-permissions!`."
  cli-config/backup-cursor-permissions!)

(def ^{:stratum 0} cleanup-codex-mcp-config!
  "Alias of `cli-config/cleanup-codex-mcp-config!`."
  cli-config/cleanup-codex-mcp-config!)

(def ^{:stratum 0} cleanup-cursor-mcp-config!
  "Alias of `cli-config/cleanup-cursor-mcp-config!`."
  cli-config/cleanup-cursor-mcp-config!)

(def ^{:stratum 0} cleanup-cursor-permissions!
  "Alias of `cli-config/cleanup-cursor-permissions!`."
  cli-config/cleanup-cursor-permissions!)

(def ^{:stratum 0} hook-eval-command
  "Alias of `cli-config/hook-eval-command`."
  cli-config/hook-eval-command)

(def ^{:stratum 0} command-on-path?
  "Alias of `cli-config/command-on-path?`."
  cli-config/command-on-path?)

(def ^{:stratum 0} cursor-permission-allow
  "Alias of `cli-config/cursor-permission-allow`."
  cli-config/cursor-permission-allow)

(def ^{:stratum 0} cursor-permission-deny
  "Alias of `cli-config/cursor-permission-deny`."
  cli-config/cursor-permission-deny)

(def ^{:stratum 0} instant-str?
  "Alias of `parsing/instant-str?`."
  parsing/instant-str?)

(def ^{:stratum 0} key-ends-with?
  "Alias of `parsing/key-ends-with?`."
  parsing/key-ends-with?)

(def ^{:stratum 0} mcp-tools
  "Alias of `cli-config/mcp-tools`."
  cli-config/mcp-tools)

(def ^{:stratum 0} merge-answer-logs
  "Alias of `parsing/merge-answer-logs`."
  parsing/merge-answer-logs)

(def ^{:stratum 0} parse-uuid-strings
  "Alias of `parsing/parse-uuid-strings`."
  parsing/parse-uuid-strings)

(def ^{:stratum 0} read-artifact
  "Alias of `readers/read-artifact`."
  readers/read-artifact)

(def ^{:stratum 0} read-capsule-artifact
  "Alias of `readers/read-capsule-artifact`."
  readers/read-capsule-artifact)

(def ^{:stratum 0} read-capsule-codex-answers
  "Alias of `readers/read-capsule-codex-answers`."
  readers/read-capsule-codex-answers)

(def ^{:stratum 0} read-capsule-session-outputs
  "Alias of `readers/read-capsule-session-outputs`."
  readers/read-capsule-session-outputs)

(def ^{:stratum 0} read-capsule-worktree-artifact
  "Alias of `readers/read-capsule-worktree-artifact`."
  readers/read-capsule-worktree-artifact)

(def ^{:stratum 0} read-codex-answers
  "Alias of `readers/read-codex-answers`."
  readers/read-codex-answers)

(def ^{:stratum 0} read-context-misses
  "Alias of `readers/read-context-misses`."
  readers/read-context-misses)

(def ^{:stratum 0} read-context-reads
  "Alias of `readers/read-context-reads`."
  readers/read-context-reads)

(def ^{:stratum 0} read-worktree-artifact
  "Alias of `readers/read-worktree-artifact`."
  readers/read-worktree-artifact)

(def ^{:stratum 0} resolve-miniforge-command
  "Alias of `cli-config/resolve-miniforge-command`."
  cli-config/resolve-miniforge-command)

(def ^{:stratum 0} server-command
  "Alias of `cli-config/server-command`."
  cli-config/server-command)

(def ^{:stratum 0} uuid-str?
  "Alias of `parsing/uuid-str?`."
  parsing/uuid-str?)

(def ^{:stratum 0} write-claude-settings!
  "Alias of `cli-config/write-claude-settings!`."
  cli-config/write-claude-settings!)

(def ^{:stratum 0} write-cursor-permissions!
  "Alias of `cli-config/write-cursor-permissions!`."
  cli-config/write-cursor-permissions!)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} write-capsule-mcp-config!
  "Write MCP config and Claude settings inside the capsule.
   The server command resolves to capsule-local bb/miniforge binary:
   `bb miniforge` runs the CLI, whose subcommand is `context-server`
   (`mcp-context-server` is the name of the bb task, not a CLI command)."
  [session]
  (let [exec!       (:exec! session)
        executor    (:executor session)
        env-id      (:environment-id session)
        session-dir (:dir session)
        ;; Inside the capsule, bb and miniforge are available directly
        srv-cmd     {:command "bb" :args ["miniforge" "context-server"
                                          "--artifact-dir" session-dir]}
        mcp-config  (json/generate-string
                     {"mcpServers" {"context" {"command" (:command srv-cmd)
                                               "args"    (:args srv-cmd)}}}
                     {:pretty true})
        hook-cmd    (hook-eval-command ["bb" "miniforge"] (:role session))
        settings    (json/generate-string (cli-config/pre-tool-use-settings hook-cmd) {:pretty true})]
    ;; Write configs inside capsule
    (exec! executor env-id (str "cat > " (file-artifacts/shell-quote (:mcp-config-path session)) " << 'MCPEOF'\n" mcp-config "\nMCPEOF")
           {:workdir (:workdir session)})
    (exec! executor env-id (str "cat > " (file-artifacts/shell-quote (str session-dir "/claude-settings.json")) " << 'SETTINGSEOF'\n" settings "\nSETTINGSEOF")
           {:workdir (:workdir session)})
    (assoc session
           :mcp-allowed-tools mcp-tools
           :supervision {:hook-eval-cmd hook-cmd
                         :settings-path (str session-dir "/claude-settings.json")
                         :policy :workspace-write})))

(defn ^{:stratum 1} write-cursor-permissions-for-session!
  "Rewrite the session's .cursor/cli.json allowlist with a role's
   `disallowed-tools` applied (e.g. a reviewer that must not Write).

   No-op for capsule sessions — Cursor configs are host-only. Returns the
   path, or nil when skipped."
  [session disallowed-tools]
  (when-not (:capsule? session)
    (write-cursor-permissions! (:config-root session)
                               (:mcp-allowed-tools session)
                               disallowed-tools)))

(defn ^{:stratum 1} write-mcp-config!
  "Write session config files for all supported CLI backends.

   Writes config files:
   - <session-dir>/mcp-config.json — MCP server config (for --mcp-config flag)
   - <session-dir>/claude-settings.json — Claude CLI hooks (passed via --settings)
   - .codex/config.toml — Codex MCP server config (cleaned up after session)
   - .cursor/mcp.json — Cursor MCP server config (cleaned up after session)
   - .cursor/cli.json — Cursor default-deny permission allowlist
     (cleaned up after session)

   Also populates :mcp-allowed-tools and :supervision on the session, and
   tracks the project-scoped files (.codex/config.toml, .cursor/mcp.json,
   .cursor/cli.json) in :mcp-cleanup-files for teardown.

   Arguments:
   - session - Session map from create-session!

   Returns: session (for threading)"
  [session]
  (let [srv-cmd       (server-command (:dir session) (:source-root session) (:workdir session))
        config-root   (:config-root session)
        mcp-config    {"mcpServers" {"context" {"command" (:command srv-cmd)
                                                "args"    (:args srv-cmd)}}}
        _             (spit (:mcp-config-path session)
                            (json/generate-string mcp-config {:pretty true}))
        settings-path (write-claude-settings! (:dir session) (:role session))
        codex-path    (cli-config/write-codex-mcp-config! config-root srv-cmd)
        cursor-path   (cli-config/write-cursor-mcp-config! config-root srv-cmd)
        _             (backup-cursor-permissions! config-root)
        cursor-perms  (write-cursor-permissions! config-root mcp-tools nil)
        hook-eval-cmd (hook-eval-command (resolve-miniforge-command) (:role session))]
    (assoc session
           :mcp-allowed-tools mcp-tools
           :mcp-cleanup-files [codex-path cursor-path cursor-perms]
           :supervision {:hook-eval-cmd hook-eval-cmd
                         :settings-path settings-path
                         :policy :workspace-write
                         :task-context (:task-context session)
                         :phase (:phase session)})))

(defn ^{:stratum 1} cleanup-session!
  "Delete the temporary session directory and clean up injected configs.

   Removes the [mcp_servers.artifact] block from .codex/config.toml and the
   artifact entry from .cursor/mcp.json (preserving any other config), and
   restores .cursor/cli.json to its exact pre-session contents from the
   backup snapshot (or deletes it if miniforge created it).

   Arguments:
   - session - Session map from create-session!"
  [session]
  ;; Clean up injected MCP entries from project-scoped config files
  (doseq [path (:mcp-cleanup-files session)]
    (cond
      (str/ends-with? path "config.toml") (cleanup-codex-mcp-config! path)
      (str/ends-with? path "mcp.json") (cleanup-cursor-mcp-config! path)
      (str/ends-with? path "cli.json") (cleanup-cursor-permissions! path)))
  ;; Delete temp session directory
  (try
    (let [dir (io/file (:dir session))]
      (doseq [f (reverse (file-seq dir))]
        (.delete ^java.io.File f)))
    (catch Exception _ nil)))

(defn ^{:stratum 1} validate-session
  "Validate a session map against the Session schema.

   Returns {:valid? true} or {:valid? false :errors ...}."
  [session]
  (if (m/validate Session session)
    {:valid? true}
    {:valid? false
     :errors (m/explain Session session)}))

(defn ^{:stratum 1} write-context-cache-for-session!
  "Write context cache to the appropriate location based on session type.
   Dispatches to capsule or host variant based on :capsule? flag."
  [session files]
  (if (:capsule? session)
    (write-capsule-context-cache! session files)
    (write-context-cache! session files)))

(defn ^{:stratum 1} create-capsule-session!
  "Create an artifact session inside a task capsule.
   Session directory is created inside the capsule's workspace via executor.
   Returns session map with capsule-relative paths and resolved exec! fn.

   Three-arity form fails with a configuration error because agent must not
   reach upward into dag-executor. Four-arity form accepts the execute-fn
   injected by workflow."
  ([executor env-id workdir]
   (create-capsule-session! executor env-id workdir (missing-execute-fn!)))
  ([executor env-id workdir execute-fn]
   (let [exec!       execute-fn
         session-dir (str workdir "/.miniforge-session")
         _           (exec! executor env-id (str "mkdir -p " (file-artifacts/shell-quote session-dir)) {:workdir workdir})]
     {:dir               session-dir
      :mcp-config-path   (str session-dir "/mcp-config.json")
      :artifact-path     (str session-dir "/artifact.edn")
      :capsule?          true
      :explicit-workdir? true
      :exec!             exec!
      :executor          executor
      :environment-id    env-id
      :workdir           workdir
      :pre-session-snapshot (try
                              (file-artifacts/snapshot-via-executor
                               exec! executor env-id workdir)
                              (catch Exception _
                                (file-artifacts/empty-snapshot)))})))

;; Stays here, calling the readers through the aliases above, so tests that
;; redefine artifact-session/read-artifact and friends still steer with-session.
(defn ^{:stratum 1} read-host-session-outputs
  "Read artifact + context-misses + context-reads from a host session's
   local directory. Host-mode counterpart of `read-capsule-session-outputs`
   — same return shape, plain filesystem reads instead of an executor
   round-trip."
  [session]
  (let [{:keys [answers answer-log]} (read-codex-answers session)]
    {:artifact         (read-artifact session)
     :context-misses   (read-context-misses session)
     :context-reads    (read-context-reads session)
     :codex-answers    answers
     :codex-answer-log answer-log}))

;------------------------------------------------------------------------------ Layer 2

(defmacro ^{:stratum 2} with-artifact-session
  "Execute body with an artifact session, returning the artifact if found.

   Binds `session` in the body. After body completes, reads the artifact
   file and any context cache misses. Cleans up the temp directory regardless.

   DEPRECATED: prefer `with-session` for new call-sites. `with-session`
   supports both host and governed (capsule) modes, emits a WARN when
   neither MCP nor worktree artifacts are found, and correctly gates the
   worktree scan on an explicitly-provided worktree path. This macro
   bypasses that logic and does not emit WARN — retained only for existing
   call-sites that have not yet migrated.

   Usage:
     (with-artifact-session [session]
       (artifact-session/write-context-cache! session files-map)
       (call-llm ... {:mcp-config (:mcp-config-path session)}))"
  [[session-sym] & body]
  `(let [session# (-> (create-session!) write-mcp-config!)
         ~session-sym session#]
     (try
       (let [result# (do ~@body)]
         {:llm-result result#
          :artifact (read-artifact session#)
          :context-misses (read-context-misses session#)
          :pre-session-snapshot (:pre-session-snapshot session#)})
       (finally
         (cleanup-session! session#)))))

(defmacro ^{:stratum 2} with-capsule-artifact-session
  "Execute body with a capsule-aware artifact session (N11 §6.3).
   Like with-artifact-session but session files live inside the task capsule.
   Binding form: [session executor env-id workdir execute-fn]."
  [[session-sym executor env-id workdir execute-fn] & body]
  (let [execute-fn-form (or execute-fn
                            `(throw (ex-info
                                      "Capsule artifact sessions require :execution/execute-fn"
                                      {:missing :execution/execute-fn
                                       :component :agent/artifact-session})))]
    `(let [session# (-> (create-capsule-session! ~executor ~env-id ~workdir ~execute-fn-form)
                        write-capsule-mcp-config!)
           ~session-sym session#]
       (try
         (let [result# (do ~@body)]
           {:llm-result result#
            :artifact (read-capsule-artifact session#)})
         (finally
           (cleanup-capsule-session! session#))))))

(defn ^{:stratum 2} with-readonly-session
  "Like `with-session`, but for READ-ONLY agents (e.g. the reviewer) that
   consume the MCP context cache but produce NO worktree artifact.

   Sets up the session + MCP config (host or governed/capsule, mirroring
   `with-session`), runs `body-fn`, and cleans up — but skips the artifact
   read + the nothing-found WARN that `run-session` does, since a reviewer
   has no work product to promote.

   A read-only session still produces one output: the §7.7.2 answer log
   (answers.edn), written by the MCP server when the agent records
   explicit peg answers. It is read BEFORE cleanup — previously the
   session directory was deleted with the log unread, so a reviewer's
   recorded answers never reached the durable consultation record.

   Returns {:llm-result <body-fn result>
            :codex-answers <vector-or-nil>
            :codex-answer-log :absent|:recorded|:unreadable}.

   Use when an agent needs cached reads (context_read/grep/glob) for
   exploration but does not write files. The hook's role comes from
   `context` as for `with-session`."
  [context body-fn]
  (let [run (fn [session read-answers-fn cleanup-fn]
              (try
                (let [result (body-fn session)
                      {:keys [answers answer-log]} (read-answers-fn session)]
                  {:llm-result       result
                   :codex-answers    answers
                   :codex-answer-log answer-log})
                (finally (cleanup-fn session))))]
    (if (governed? context)
      (let [executor (:execution/executor context)
            env-id   (:execution/environment-id context)
            workdir  (or (:execution/capsule-workdir context) (:execution/worktree-path context) "/workspace")
            exec!    (or (:execution/execute-fn context) (missing-execute-fn!))
            session  (-> (create-capsule-session! executor env-id workdir exec!)
                         (assoc :role (:agent/role context))
                         write-capsule-mcp-config!)]
        (run session read-capsule-codex-answers cleanup-capsule-session!))
      (let [workdir (:execution/worktree-path context)
            session (-> (if workdir
                          (create-session! {:workdir workdir
                                            :source-root (:source-root context)})
                          (create-session! {:source-root (:source-root context)}))
                        (assoc :role (:agent/role context))
                        write-mcp-config!)]
        (run session read-codex-answers cleanup-session!)))))

(defn ^{:stratum 2} with-session
  "Execute body-fn with the appropriate artifact session for the execution mode.

   In governed mode (context has :execution/executor, :execution/environment-id,
   and :execution/mode :governed), creates a capsule session inside the Docker
   container so the MCP server and hooks run inside the capsule boundary.
   Otherwise, creates a host session on the local filesystem.

   The session's PreToolUse hook enforces the tool policy
   (config/agent/tool-policy.edn) of the context's `:agent/role`, which
   `specialized/create-base-agent` sets on every call into an agent.
   Without one, the hook refuses every tool.

   Arguments:
   - context - Execution context map (from workflow runner)
   - body-fn - (fn [session] ...) that receives the session and returns LLM result

   Returns normalized map:
   {:llm-result :artifact :worktree-artifacts :context-misses :context-reads
    :codex-answers :codex-answer-log :pre-session-snapshot :session-mode}"
  [context body-fn]
    (if (governed? context)
      (let [executor (:execution/executor context)
            env-id   (:execution/environment-id context)
            workdir  (or (:execution/capsule-workdir context) (:execution/worktree-path context) "/workspace")
            exec!    (or (:execution/execute-fn context) (missing-execute-fn!))
            session  (-> (create-capsule-session! executor env-id workdir exec!)
                       (assoc :role (:agent/role context))
                       write-capsule-mcp-config!)]
      (run-session session body-fn read-capsule-session-outputs cleanup-capsule-session! :capsule))
    (let [workdir (:execution/worktree-path context)
          session (-> (if workdir
                        (create-session! {:workdir workdir
                                          :source-root (:source-root context)})
                        (create-session! {:source-root (:source-root context)}))
                      (assoc :role (:agent/role context))
                      write-mcp-config!)]
      (run-session session body-fn read-host-session-outputs cleanup-session! :host))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a session
  (def s (-> (create-session!) write-mcp-config!))
  s
  ;; => {:dir "/tmp/miniforge-artifact-xxx"
  ;;     :mcp-config-path "/tmp/miniforge-artifact-xxx/mcp-config.json"
  ;;     :artifact-path "/tmp/miniforge-artifact-xxx/artifact.edn"}

  ;; Check config was written
  (slurp (:mcp-config-path s))

  ;; Read artifact (returns nil if not written yet)
  (read-artifact s)

  ;; Cleanup
  (cleanup-session! s)

  :leave-this-here)
