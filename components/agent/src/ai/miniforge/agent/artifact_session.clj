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

   Layer 0: Session lifecycle
   Layer 1: MCP config generation
   Layer 2: Artifact reading
   Layer 3: High-level session macro"
  (:require
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0
;; Schema & session lifecycle

(def Session
  "Schema for an artifact session map."
  [:map
   [:dir [:string {:min 1}]]
   [:mcp-config-path [:string {:min 1}]]
   [:artifact-path [:string {:min 1}]]])

(defn validate-session
  "Validate a session map against the Session schema.

   Returns {:valid? true} or {:valid? false :errors ...}."
  [session]
  (if (m/validate Session session)
    {:valid? true}
    {:valid? false
     :errors (m/explain Session session)}))

(defn command-on-path?
  "Check if a command exists on PATH."
  [cmd]
  (try
    (let [proc (.exec (Runtime/getRuntime) (into-array String ["which" cmd]))]
      (zero? (.waitFor proc)))
    (catch Exception _ false)))

(defn- find-bb-root
  "Find the nearest ancestor directory containing bb.edn."
  []
  (loop [dir (.getCanonicalFile (io/file (System/getProperty "user.dir")))]
    (let [bb-file (io/file dir "bb.edn")]
      (cond
        (.exists bb-file) (.getPath dir)
        (nil? (.getParentFile dir)) nil
        :else (recur (.getParentFile dir))))))

(defn resolve-miniforge-command
  "Resolve the miniforge command to use for spawning subprocesses.

   Resolution order:
   1. MINIFORGE_CMD env var (explicit override, e.g. \"/usr/local/bin/mf\")
   2. [\"bb\" \"--config\" <root>/bb.edn \"--deps-root\" <root> \"miniforge\"]
      (dev mode — location-independent bb.edn task)
   3. \"miniforge\" on PATH (installed binary)
   4. [\"bb\" \"miniforge\"] as a final fallback"
  []
  (let [bb-root (find-bb-root)]
    (cond
      ;; 1. Explicit override
      (System/getenv "MINIFORGE_CMD")
      [(System/getenv "MINIFORGE_CMD")]

      ;; 2. Prefer the current workspace in development and tests.
      bb-root
      ["bb" "--config" (str bb-root "/bb.edn") "--deps-root" bb-root "miniforge"]

      ;; 3. Installed binary on PATH
      (command-on-path? "miniforge")
      ["miniforge"]

      ;; 4. Last-resort fallback
      :else
      ["bb" "miniforge"])))

(defn create-session!
  "Create a new artifact session with a temporary directory.

   Returns:
   - {:dir <path>, :mcp-config-path <path>, :artifact-path <path>}"
  []
  (let [working-dir (System/getProperty "user.dir")
        dir (str (Files/createTempDirectory
                  "miniforge-artifact-"
                  (into-array FileAttribute [])))
        config-path (str dir "/mcp-config.json")
        artifact-path (str dir "/artifact.edn")]
    {:dir dir
     :mcp-config-path config-path
     :artifact-path artifact-path
     :pre-session-snapshot (try
                             (file-artifacts/snapshot-working-dir working-dir)
                             (catch Exception _
                               (file-artifacts/empty-snapshot)))}))

;------------------------------------------------------------------------------ Layer 1
;; MCP server command

(defn server-command
  "Build the MCP context server command for a given session directory.

   Returns {:command <string> :args [<string> ...]} suitable for MCP config JSON.

   The server reads context-cache.edn from artifact-dir on startup and
   serves context_read/context_grep/context_glob to the inner LLM agent."
  [artifact-dir]
  (let [bb-root (find-bb-root)]
    (if bb-root
      {:command "bb"
       :args ["--config" (str bb-root "/bb.edn")
              "--deps-root" bb-root
              "projects/mcp-context-server/main.bb"
              "--artifact-dir" artifact-dir]}
      {:command "bb"
       :args ["projects/mcp-context-server/main.bb"
              "--artifact-dir" artifact-dir]})))

;------------------------------------------------------------------------------ Layer 1
;; MCP config generation

(defn write-codex-mcp-config!
  "Write or update .codex/config.toml with [mcp_servers.artifact] block.

   If the file exists and already has an [mcp_servers.artifact] block,
   replaces it. Otherwise appends the block. Creates .codex/ dir if needed.

   Returns the path to the config file."
  [server-cmd]
  (let [{:keys [command args]} server-cmd
        dir (io/file ".codex")
        config-file (io/file dir "config.toml")
        block-header "[mcp_servers.artifact]"
        block (str block-header "\n"
                   "command = " (json/generate-string command) "\n"
                   "args = " (json/generate-string args) "\n")]
    (.mkdirs dir)
    (if (.exists config-file)
      (let [content (slurp config-file)]
        (if (str/includes? content block-header)
          ;; Replace existing block (up to next section or EOF)
          (let [replaced (str/replace content
                                      #"(?s)\[mcp_servers\.artifact\]\n(?:(?!\n\[).)*"
                                      block)]
            (spit config-file replaced))
          ;; Append
          (spit config-file (str content "\n" block) :append false)))
      (spit config-file block))
    (str config-file)))

(defn write-cursor-mcp-config!
  "Write or update .cursor/mcp.json with mcpServers.artifact entry.

   If the file exists, merges into existing JSON. Creates .cursor/ dir if needed.

   Returns the path to the config file."
  [server-cmd]
  (let [{:keys [command args]} server-cmd
        dir (io/file ".cursor")
        config-file (io/file dir "mcp.json")
        entry {"command" command "args" args}
        existing (when (.exists config-file)
                   (try (json/parse-string (slurp config-file))
                        (catch Exception _ {})))
        config (assoc-in (or existing {}) ["mcpServers" "artifact"] entry)]
    (.mkdirs dir)
    (spit config-file (json/generate-string config {:pretty true}))
    (str config-file)))

(defn write-claude-settings!
  "Write Claude CLI settings JSON with PreToolUse hook for supervision.

   The hook invokes `bb miniforge hook-eval` which reads the tool request
   from stdin and returns allow/deny on stdout.

   Returns the path to the settings file."
  [session-dir]
  (let [[cmd & args] (resolve-miniforge-command)
        hook-command (str/join " " (concat [cmd] args ["hook-eval"]))
        settings {"hooks"
                  {"PreToolUse"
                   [{"type" "command"
                     "command" hook-command}]}}
        path (str session-dir "/claude-settings.json")]
    (spit path (json/generate-string settings {:pretty true}))
    path))

(def mcp-tool-names
  "MCP tool names exposed by the context server."
  ["context_read" "context_grep" "context_glob"])

(defn write-mcp-config!
  "Write session config files for all supported CLI backends.

   Writes config files:
   - <session-dir>/mcp-config.json — MCP server config (for --mcp-config flag)
   - <session-dir>/claude-settings.json — Claude CLI hooks (passed via --settings)
   - .codex/config.toml — Codex MCP server config (cleaned up after session)
   - .cursor/mcp.json — Cursor MCP server config (cleaned up after session)

   Also populates :mcp-allowed-tools and :supervision on the session.

   Arguments:
   - session - Session map from create-session!

   Returns: session (for threading)"
  [session]
  (let [srv-cmd       (server-command (:dir session))
        mcp-config    {"mcpServers" {"context" {"command" (:command srv-cmd)
                                                "args"    (:args srv-cmd)}}}
        _             (spit (:mcp-config-path session)
                            (json/generate-string mcp-config {:pretty true}))
        settings-path (write-claude-settings! (:dir session))
        codex-path    (write-codex-mcp-config! srv-cmd)
        cursor-path   (write-cursor-mcp-config! srv-cmd)
        [hook-cmd & hook-args] (resolve-miniforge-command)
        hook-eval-cmd (str/join " " (concat [hook-cmd] hook-args ["hook-eval"]))]
    (assoc session
           :mcp-allowed-tools mcp-tool-names
           :mcp-cleanup-files [codex-path cursor-path]
           :supervision {:hook-eval-cmd hook-eval-cmd
                         :settings-path settings-path
                         :policy :workspace-write
                         :task-context (:task-context session)
                         :phase (:phase session)})))

(defn write-context-cache!
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

;------------------------------------------------------------------------------ Layer 2
;; Artifact reading

(defn uuid-str?
  "Check if a value is a UUID-shaped string."
  [v]
  (and (string? v)
       (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" v)))

(defn instant-str?
  "Check if a value looks like an ISO instant string."
  [v]
  (and (string? v)
       (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*" v)))

(defn key-ends-with?
  "Check if a namespaced keyword ends with the given suffix."
  [k suffix]
  (and (keyword? k)
       (str/ends-with? (name k) suffix)))

(defn parse-uuid-strings
  "Convert string UUIDs and ISO instant strings in an artifact map.
   The MCP server writes UUIDs and instants as strings since it runs in babashka.

   Pattern-based detection:
   - Any key ending in `/id` with a UUID-shaped string → java.util.UUID
   - Any key ending in `/created-at` with an ISO instant string → java.util.Date
   - Any vector of maps containing `:task/id` → recurse into those maps"
  [m]
  (cond
    (map? m)
    (into {}
          (map (fn [[k v]]
                 [k (cond
                      ;; Any key ending in /id with a UUID string
                      (and (key-ends-with? k "id") (uuid-str? v))
                      (java.util.UUID/fromString v)

                      ;; Any key ending in /created-at with an instant string
                      (and (key-ends-with? k "created-at") (instant-str? v))
                      (try (java.util.Date/from (java.time.Instant/parse v))
                           (catch Exception _ (java.util.Date.)))

                      ;; Vector of maps that may contain nested UUIDs/instants
                      (and (vector? v) (seq v) (map? (first v)))
                      (mapv (fn [item]
                              (parse-uuid-strings item))
                            v)

                      :else v)]))
          m)

    :else m))

(defn read-artifact
  "Read the artifact EDN file from a session directory.

   Arguments:
   - session - Session map from create-session!

   Returns:
   - Parsed artifact map with proper UUID types, or nil if not found.
     Logs warnings on missing file or parse failure instead of silently returning nil."
  [session]
  (let [f (io/file (:artifact-path session))]
    (if-not (.exists f)
      (do
        (binding [*out* *err*]
          (println "ERROR: artifact file not found at" (:artifact-path session)
                   "— MCP tool was likely not called by the LLM"))
        nil)
      (try
        (-> (slurp f)
            edn/read-string
            parse-uuid-strings)
        (catch Exception e
          (binding [*out* *err*]
            (println "WARN: failed to parse artifact at" (:artifact-path session)
                     "—" (ex-message e)))
          nil)))))

(defn read-context-misses
  "Read context cache misses from the session directory.

   The MCP server writes context-misses.edn on exit with records of
   every cache miss (files the agent needed but weren't pre-loaded).

   Returns: vector of miss records, or nil if no misses file."
  [session]
  (let [path (str (:dir session) "/context-misses.edn")
        f (io/file path)]
    (when (.exists f)
      (try
        (edn/read-string (slurp f))
        (catch Exception e
          (binding [*out* *err*]
            (println "WARN: failed to parse context misses:" (ex-message e)))
          nil)))))

;------------------------------------------------------------------------------ Layer 3
;; High-level session helpers

(defn cleanup-codex-mcp-config!
  "Remove [mcp_servers.artifact] block from .codex/config.toml.
   Deletes the file if it becomes empty."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (let [content (slurp f)
            cleaned (str/replace content
                                 #"(?s)\n?\[mcp_servers\.artifact\]\n(?:(?!\n\[).)*"
                                 "")]
        (if (str/blank? (str/trim cleaned))
          (.delete f)
          (spit f cleaned))))))

(defn cleanup-cursor-mcp-config!
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

(defn cleanup-session!
  "Delete the temporary session directory and clean up injected MCP configs.

   Removes the [mcp_servers.artifact] block from .codex/config.toml
   and the artifact entry from .cursor/mcp.json, preserving any other
   config in those files.

   Arguments:
   - session - Session map from create-session!"
  [session]
  ;; Clean up injected MCP entries from project-scoped config files
  (doseq [path (:mcp-cleanup-files session)]
    (cond
      (str/ends-with? path "config.toml") (cleanup-codex-mcp-config! path)
      (str/ends-with? path "mcp.json") (cleanup-cursor-mcp-config! path)))
  ;; Delete temp session directory
  (try
    (let [dir (io/file (:dir session))]
      (doseq [f (reverse (file-seq dir))]
        (.delete ^java.io.File f)))
    (catch Exception _ nil)))

(defmacro with-artifact-session
  "Execute body with an artifact session, returning the artifact if found.

   Binds `session` in the body. After body completes, reads the artifact
   file and any context cache misses. Cleans up the temp directory regardless.

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
