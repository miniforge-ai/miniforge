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
(ns ai.miniforge.agent.artifact-session.cli-config
  "Agent CLI config an artifact session writes: the miniforge and MCP
   server commands, the Claude PreToolUse hook settings, the Codex MCP
   block, and the Cursor MCP config and permission allowlist, with their
   cleanup."
  (:require
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.agent.tool-supervisor :as tool-supervisor]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} command-on-path?
  "Check if a command exists on PATH. `fs/which` resolves it on every OS
   (with PATHEXT on Windows, which has no `which` binary)."
  [cmd]
  (boolean (some-> cmd fs/which)))

(defn- ^{:stratum 0} find-bb-root
  "Find the nearest ancestor directory whose bb.edn is miniforge's own: its
   :tasks define the `miniforge` and `mcp-context-server` tasks that the
   MCP server and PreToolUse hook commands run.

   Any other bb.edn (a user repository's own, or one that does not parse)
   is skipped. Taking it would point both commands at tasks that do not
   exist there: the MCP server would not start, and the PreToolUse hook
   would fail and so block every tool (`hook-eval-command`)."
  []
  (loop [dir (.getCanonicalFile (io/file (System/getProperty "user.dir")))]
    (let [bb-file (io/file dir "bb.edn")
          tasks   (when (.isFile bb-file)
                    (try+
                     (:tasks (edn/read-string {:default tagged-literal}
                                              (slurp bb-file)))
                     (catch Exception _ nil)))]
      (cond
        (and (map? tasks)
             (contains? tasks 'miniforge)
             (contains? tasks 'mcp-context-server))
        (.getPath dir)

        (nil? (.getParentFile dir)) nil
        :else (recur (.getParentFile dir))))))

(def ^{:stratum 0} ^:private codex-artifact-table-pattern
  #"^\[mcp_servers\.artifact(?:\..+)?\]\s*$")

(def ^{:stratum 0} ^:private toml-table-pattern
  #"^\[[^]]+\]\s*$")

(defn ^{:stratum 0} write-cursor-mcp-config!
  "Write or update .cursor/mcp.json with mcpServers.artifact entry.

   If the file exists, merges into existing JSON. Creates .cursor/ dir if needed.

   Returns the path to the config file."
  [config-root server-cmd]
  (let [{:keys [command args]} server-cmd
        root (or config-root (System/getProperty "user.dir"))
        dir (io/file root ".cursor")
        config-file (io/file dir "mcp.json")
        entry {"command" command "args" args}
        existing (when (.exists config-file)
                   (try (json/parse-string (slurp config-file))
                        (catch Exception _ {})))
        config (assoc-in (or existing {}) ["mcpServers" "artifact"] entry)]
    (.mkdirs dir)
    (spit config-file (json/generate-string config {:pretty true}))
    (str config-file)))

(defn- ^{:stratum 0} mcp-tool->cursor-permission
  "Translate one agent-agnostic `mcp-tools` entry into a Cursor permission
   rule string (https://cursor.com/docs/cli/reference/permissions).

     {:mcp/server S :mcp/tool T} -> \"Mcp(S:T)\"
     :Write / :Edit / :MultiEdit -> \"Write(**)\"  (Cursor models all edits
                                                    as writes; no Edit primitive)

   Returns nil for entries with no Cursor analog."
  [tool]
  (cond
    (map? tool)
    (format "Mcp(%s:%s)" (name (:mcp/server tool)) (name (:mcp/tool tool)))

    (#{:Write :Edit :MultiEdit} tool)
    "Write(**)"

    :else nil))

(def ^{:stratum 0} cursor-permission-deny
  "Hard denials applied regardless of the allowlist. Deny takes precedence
   over allow in Cursor, so these block secret writes even when Write(**) is
   allowed. Globs follow Cursor's documented Write(pathOrGlob) form."
  ["Write(**/.env*)" "Write(**/*.key)" "Write(**/*.pem)"])

(def ^{:stratum 0} ^:private cursor-write-tools
  "Native tools that Cursor collapses into the single Write(**) permission.
   Cursor cannot express a partial write restriction, so disallowing any one
   of these drops the whole write permission."
  #{:Write :Edit :MultiEdit})

(def ^{:stratum 0} ^:private cursor-permissions-backup-suffix
  "Suffix for the pre-session .cursor/cli.json snapshot used to restore the
   user's exact file on cleanup."
  ".mf-bak")

(def ^{:stratum 0} mcp-tools
  "Tools the inner agent is auto-approved to call, as generic data.

   Each backend adapter (claude, codex, cursor-agent, …) translates
   this into the allowlist / approval shape its CLI expects. Keep
   agent-CLI-agnostic — the structured form is the source of truth.

   Two entry shapes:
   - `{:mcp/server :mcp/tool}` — a tool exposed by an MCP server
   - plain keyword — a native tool from the agent CLI (e.g. `:Write`,
     needed for plan.edn / code container-promotion writes)

   Example wire formats per backend:
     claude  → --allowedTools \"mcp__context__context_read,Write,...\"
     cursor  → --approve-mcps  (blanket approve; no names needed)
     codex   → (currently unused; codex uses its own MCP config)

   Iter-10 failure — bare names like `context_read` were passed to
   --allowedTools without the `mcp__<server>__` prefix, so every MCP
   call got permission-denied. Iter-15 follow-on — `Write` wasn't in
   the allowlist at all, so the planner's `.miniforge/plan.edn`
   Write was silently denied (model then narrated 'let me try Edit'
   and stalled). 2026-05-04 follow-on — `Edit` / `MultiEdit` were
   never added either; the implementer in the
   event-log-tool-visibility dogfood spent five repair iters
   producing patch text the CLI refused to apply, then a sixth
   iter discovered Write worked while Edit did not. Both issues trace
   back to keeping the structured form honest and adapters translating
   at the CLI boundary."
  ;; No `:submit` — the artifact is the worktree/container diff (promotion),
  ;; the definitive channel. Models trained to their native tools ignore an
  ;; opt-in submit tool anyway (84:0 in dogfood), and a submit-without-files
  ;; turn is exactly the empty-disk failure we want to eliminate. Files are
  ;; the def; the curator derives the summary from the diff.
  [{:mcp/server :context :mcp/tool :context_read}
   {:mcp/server :context :mcp/tool :context_grep}
   {:mcp/server :context :mcp/tool :context_glob}
   ;; Thesium Codex pull surface (SPEC §7.1). Push delivery is the
   ;; phase-start pin (phase-software-factory codex-pin); this lets an
   ;; agent RE-consult mid-run for a different situation.
   {:mcp/server :context :mcp/tool :consider_situation}
   ;; The explicit half of the §7.7 answer channel: records the agent's
   ;; answer to a presented peg (answers.edn beside context-reads.edn).
   {:mcp/server :context :mcp/tool :answer_peg}
   ;; Agent-agnostic edit path: writes to the worktree + refreshes the cache,
   ;; needs no prior native Read. The reliable way to modify EXISTING files
   ;; across Claude/Codex/Cursor (native Write/Edit require a prior Read, which
   ;; the implementer disallows to force the cache).
   {:mcp/server :context :mcp/tool :context_write}
   ;; Native write tools: implementer needs all three patch shapes.
   ;; `Write` for full-file rewrites (planner's plan.edn, implementer
   ;; new files, releaser PR drafts). `Edit` for single-region patches.
   ;; `MultiEdit` for batched edits within one file. Roles that shouldn't
   ;; modify files filter via :disallowed-tools separately.
   :Write
   :Edit
   :MultiEdit])

(defn ^{:stratum 0} cleanup-cursor-mcp-config!
  "Remove artifact key from .cursor/mcp.json.
   Deletes the file if mcpServers becomes empty."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (try
        (let [config (json/parse-string (slurp f))
              servers (dissoc (get config "mcpServers" {}) "artifact")
              config' (if (empty? servers)
                        (dissoc config "mcpServers")
                        (assoc config "mcpServers" servers))]
          (if (empty? config')
            (.delete f)
            (spit f (json/generate-string config' {:pretty true}))))
        (catch Exception _ nil)))))

(defn ^{:stratum 0} hook-eval-command
  "The PreToolUse hook command for a session of `role` in the worktree
   `root`: the miniforge command `words` (e.g. [\"mf\"] or
   [\"bb\" \"miniforge\"]) running `hook-eval --role <role> --root <root>`,
   then `|| exit 2`. Claude runs the command
   through `sh -c` and blocks the tool call only on exit code 2, so a hook
   that cannot start (miniforge not on PATH, a crash before it replies)
   blocks too instead of letting the call through. A nil `role` leaves
   `--role` off, and hook-eval then refuses every tool; a nil `root`
   leaves `--root` off, and it refuses every file tool.

   A keyword role keeps its namespace (`:custom/implementer` becomes
   `--role custom/implementer`), so a role the policy does not list is
   refused rather than read as a listed role with the same name.

   A word holding anything beyond letters, digits and `_./:=@%+,-` is
   shell-quoted, so a checkout path with a space stays one argument."
  [words role root]
  (let [word (fn [w]
               (if (re-matches #"[A-Za-z0-9_./:=@%+,-]+" w)
                 w
                 (file-artifacts/shell-quote w)))]
    (str/join " " (concat (map word words)
                          ["hook-eval"]
                          (when role ["--role" (word (if (keyword? role)
                                                        (subs (str role) 1)
                                                        (str role)))])
                          (when root ["--root" (word (str root))])
                          ["||" "exit" "2"]))))

(defn ^{:stratum 0} pre-tool-use-settings
  "Claude Code settings that run `hook-cmd` before every tool call. Each
   PreToolUse entry is a matcher group, {\"matcher\" ... \"hooks\" [handler]},
   as https://code.claude.com/docs/en/hooks and the settings JSON schema
   require (the group's \"hooks\" key is required, and no other keys are
   allowed); \"*\" matches every tool. A handler placed directly in the
   PreToolUse array is not a valid entry, so Claude never runs it.

   `allowed-tools` (the role's tools in the tool policy) go into
   permissions.allow. A session runs Claude with `--setting-sources \"\"
   --permission-mode dontAsk`, so these are the only allow rules it has:
   a tool the role does not list is refused even if the hook let it by,
   and a call Claude's own checks would ask about is refused, not run."
  [hook-cmd allowed-tools]
  {"permissions" {"allow" (vec (sort allowed-tools))}
   "hooks" {"PreToolUse" [{"matcher" "*"
                           "hooks"   [{"type" "command" "command" hook-cmd}]}]}})

(defn ^{:stratum 0} role-allowed-tools
  "The tools `role` may call under the shipped tool policy, or none for a
   role it does not list."
  [role]
  (tool-supervisor/role-tools @tool-supervisor/tool-policy role))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-miniforge-command
  "Resolve the miniforge command to use for spawning subprocesses.

   Resolution order:
   1. MINIFORGE_CMD env var (explicit override, e.g. \"/usr/local/bin/mf\")
   2. [\"bb\" \"--config\" <root>/bb.edn \"--deps-root\" <root> \"miniforge\"]
      (dev mode — miniforge's own bb.edn in an ancestor directory)
   3. \"mf\" on PATH (installed binary: Homebrew, scoop, `bb install:local`)
   4. \"miniforge\" on PATH (full binary name)
   5. [\"bb\" \"miniforge\"] as a final fallback"
  []
  (let [bb-root (find-bb-root)]
    (cond
      ;; 1. Explicit override
      (System/getenv "MINIFORGE_CMD")
      [(System/getenv "MINIFORGE_CMD")]

      ;; 2. Prefer the current workspace in development and tests.
      bb-root
      ["bb" "--config" (str bb-root "/bb.edn") "--deps-root" bb-root "miniforge"]

      ;; 3. Installed binary on PATH, under the name the installers use
      (command-on-path? "mf")
      ["mf"]

      ;; 4. Installed binary under its full name
      (command-on-path? "miniforge")
      ["miniforge"]

      ;; 5. Last-resort fallback
      :else
      ["bb" "miniforge"])))

(defn- ^{:stratum 1} strip-codex-artifact-config
  "Remove the full mcp_servers.artifact subtree from a Codex TOML config.

   This strips both the root server block and any nested tables such as
   [mcp_servers.artifact.tools.context_read], which newer Codex builds treat
   as invalid if the parent server definition has already been removed."
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining lines
           cleaned []
           skipping? false]
      (if-let [line (first remaining)]
        (let [trimmed (str/trim line)]
          (cond
            (re-matches codex-artifact-table-pattern trimmed)
            (recur (rest remaining) cleaned true)

            (and skipping? (re-matches toml-table-pattern trimmed))
            (recur remaining cleaned false)

            skipping?
            (recur (rest remaining) cleaned true)

            :else
            (recur (rest remaining) (conj cleaned line) false)))
        (str/join "\n" cleaned)))))

;; MCP server command
(defn ^{:stratum 1} server-command
  "Build the MCP context server command for a given session directory.

   Returns {:command <string> :args [<string> ...]} suitable for MCP config JSON.

   Resolution order:
   1. MINIFORGE_MCP_CMD env var (explicit override)
   2. bb task (dev mode — miniforge's own bb.edn in an ancestor directory)
   3. mf context-server (production — bundled in the miniforge binary)
   4. miniforge context-server (fallback binary name)

   The server reads context-cache.edn from artifact-dir on startup and serves
   context_read/context_grep/context_glob and context_write to the inner LLM
   agent. `workdir` (the agent's worktree) is the context_write target."
  ([artifact-dir source-root] (server-command artifact-dir source-root nil))
  ([artifact-dir source-root workdir]
   (let [mcp-args (cond-> ["--artifact-dir" artifact-dir]
                    source-root (into ["--source-root" source-root])
                    ;; Write target for context_write — the agent's worktree.
                    ;; Embedded in every backend's MCP config (claude/codex/
                    ;; cursor all launch the server via this same command).
                    workdir     (into ["--workdir" workdir]))
         bb-root  (find-bb-root)]
    (cond
      (System/getenv "MINIFORGE_MCP_CMD")
      {:command (System/getenv "MINIFORGE_MCP_CMD") :args mcp-args}

      bb-root
      {:command "bb"
       :args (into ["--config" (str bb-root "/bb.edn")
                    "--deps-root" bb-root
                    "mcp-context-server"]
                   mcp-args)}

      (command-on-path? "mf")
      {:command "mf" :args (into ["context-server"] mcp-args)}

      :else
      {:command "miniforge" :args (into ["context-server"] mcp-args)}))))

(defn ^{:stratum 1} cursor-permission-allow
  "Cursor `allow` rules for a session: the auto-approved `mcp-tools`, minus
   any native tool the role disallows, translated to Cursor rule strings.

   Default-deny: Cursor sees only this allowlist, so shell, arbitrary file
   reads, and other MCP servers are denied by omission — no wildcard needed.

   Cursor maps Write/Edit/MultiEdit onto one Write(**) rule, so disallowing
   ANY of them drops Write(**) entirely (a finer-grained Claude distinction
   like 'Edit-but-not-Write' cannot be represented here)."
  [allowed-tools disallowed-tools]
  (let [disallowed     (set (map name disallowed-tools))
        write-blocked? (some #(contains? disallowed (name %)) cursor-write-tools)
        keep?          (fn [tool]
                         (cond
                           (map? tool)               true
                           (cursor-write-tools tool) (not write-blocked?)
                           :else                     (not (contains? disallowed (name tool)))))]
    (->> allowed-tools
         (filter keep?)
         (keep mcp-tool->cursor-permission)
         distinct
         vec)))

(defn ^{:stratum 1} backup-cursor-permissions!
  "Snapshot a pre-existing .cursor/cli.json once, before miniforge mutates
   it, so cleanup can restore it byte-for-byte.

   No-op when the file is absent (cleanup then just deletes the file we
   generate) or when a snapshot already exists (so mid-session rewrites never
   clobber the original). Returns the backup path or nil."
  [config-root]
  (let [root        (or config-root (System/getProperty "user.dir"))
        config-file (io/file root ".cursor" "cli.json")
        backup-file (io/file root ".cursor" (str "cli.json" cursor-permissions-backup-suffix))]
    (when (and (.exists config-file) (not (.exists backup-file)))
      (io/copy config-file backup-file)
      (str backup-file))))

(defn ^{:stratum 1} cleanup-cursor-permissions!
  "Restore .cursor/cli.json to its exact pre-session contents.

   If `backup-cursor-permissions!` captured a snapshot (the file pre-existed),
   move it back over our generated file; otherwise delete the file miniforge
   created. Fully non-destructive — user/project rules are byte-preserved
   regardless of any overlap with managed rules."
  [path]
  (try
    (let [config-file (io/file path)
          backup-file (io/file (str path cursor-permissions-backup-suffix))]
      (cond
        (.exists backup-file)
        (do (io/copy backup-file config-file)
            (.delete backup-file))

        (.exists config-file)
        (.delete config-file)))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2

;; MCP config generation
(defn ^{:stratum 2} write-codex-mcp-config!
  "Write or update .codex/config.toml with [mcp_servers.artifact] block.

   If the file exists and already has an [mcp_servers.artifact] block,
   replaces it. Otherwise appends the block. Creates .codex/ dir if needed.

   Sets `required = true` per Codex's config-reference so startup fails
   loudly if our MCP server can't initialize, rather than silently
   running with the artifact server absent. This mirrors Claude's
   behavior when --mcp-config can't be loaded.

   Returns the path to the config file."
  [config-root server-cmd]
  (let [{:keys [command args]} server-cmd
        root (or config-root (System/getProperty "user.dir"))
        dir (io/file root ".codex")
        config-file (io/file dir "config.toml")
        block-header "[mcp_servers.artifact]"
        block (str block-header "\n"
                   "command = " (json/generate-string command) "\n"
                   "args = " (json/generate-string args) "\n"
                   "default_tools_approval_mode = \"approve\"\n"
                   "required = true\n")]
    (.mkdirs dir)
    (if (.exists config-file)
      (let [content (slurp config-file)
            preserved (-> content strip-codex-artifact-config str/trim)]
        (spit config-file
              (if (str/blank? preserved)
                block
                (str preserved "\n\n" block))))
      (spit config-file block))
    (str config-file)))

(defn ^{:stratum 2} write-cursor-permissions!
  "Write .cursor/cli.json with a default-deny permission allowlist.

   `allowed-tools` is the agent-agnostic auto-approve set (`mcp-tools`);
   `disallowed-tools` subtracts native tools the role must not call.

   Authoritative over the rules miniforge manages: any prior managed entry
   is stripped before the role-scoped set is re-added, so rewriting with a
   narrower `disallowed-tools` correctly shrinks the allowlist. The exact
   pre-session file is captured by `backup-cursor-permissions!` and restored
   on cleanup, so user/project rules survive byte-for-byte even when they
   overlap a managed rule. Returns the path to the config file."
  [config-root allowed-tools disallowed-tools]
  (let [root          (or config-root (System/getProperty "user.dir"))
        dir           (io/file root ".cursor")
        config-file   (io/file dir "cli.json")
        allow         (cursor-permission-allow allowed-tools disallowed-tools)
        managed-allow (set (keep mcp-tool->cursor-permission allowed-tools))
        managed-deny  (set cursor-permission-deny)
        existing      (when (.exists config-file)
                        (try (json/parse-string (slurp config-file))
                             (catch Exception _ {})))
        user-allow    (remove managed-allow (get-in existing ["permissions" "allow"] []))
        user-deny     (remove managed-deny (get-in existing ["permissions" "deny"] []))
        config        (-> (or existing {})
                          (assoc-in ["permissions" "allow"]
                                    (vec (distinct (concat user-allow allow))))
                          (assoc-in ["permissions" "deny"]
                                    (vec (distinct (concat user-deny cursor-permission-deny)))))]
    (.mkdirs dir)
    (spit config-file (json/generate-string config {:pretty true}))
    (str config-file)))

(defn ^{:stratum 2} write-claude-settings!
  "Write Claude CLI settings JSON with PreToolUse hook for supervision.

   The hook runs `hook-eval --role <role> --root <workdir>` on the
   command `resolve-miniforge-command` returns (e.g. `mf hook-eval --role
   implementer --root /work/repo`), which reads the tool request from
   stdin and returns allow/deny on stdout (`hook-eval-command`).

   Returns the path to the settings file."
  [session-dir role workdir]
  (let [hook-command (hook-eval-command (resolve-miniforge-command) role workdir)
        path (str session-dir "/claude-settings.json")]
    (spit path (json/generate-string (pre-tool-use-settings hook-command (role-allowed-tools role))
                                     {:pretty true}))
    path))

;; High-level session helpers
(defn ^{:stratum 2} cleanup-codex-mcp-config!
  "Remove [mcp_servers.artifact] block from .codex/config.toml.
   Deletes the file if it becomes empty."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (let [cleaned (-> (slurp f) strip-codex-artifact-config str/trim)]
        (if (str/blank? cleaned)
          (.delete f)
          (spit f (str cleaned "\n")))))))
