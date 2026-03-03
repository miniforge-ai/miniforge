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

(defn- command-on-path?
  "Check if a command exists on PATH."
  [cmd]
  (try
    (let [proc (.exec (Runtime/getRuntime) (into-array String ["which" cmd]))]
      (zero? (.waitFor proc)))
    (catch Exception _ false)))

(defn- resolve-miniforge-command
  "Resolve the miniforge command to use for spawning subprocesses.

   Resolution order:
   1. MINIFORGE_CMD env var (explicit override, e.g. \"/usr/local/bin/mf\")
   2. \"miniforge\" on PATH (installed binary)
   3. [\"bb\" \"miniforge\"] (dev mode — bb.edn task, requires CWD at project root)"
  []
  (cond
    ;; 1. Explicit override
    (System/getenv "MINIFORGE_CMD")
    [(System/getenv "MINIFORGE_CMD")]

    ;; 2. Installed binary on PATH
    (command-on-path? "miniforge")
    ["miniforge"]

    ;; 3. Dev mode fallback (bb.edn task)
    :else
    ["bb" "miniforge"]))

(defn- server-command
  "Build the MCP config command map for starting the artifact server."
  [artifact-dir]
  (let [[cmd & args] (resolve-miniforge-command)]
    {:command cmd
     :args (vec (concat args ["mcp-serve" "--artifact-dir" artifact-dir]))}))

(defn create-session!
  "Create a new artifact session with a temporary directory.

   Returns:
   - {:dir <path>, :mcp-config-path <path>, :artifact-path <path>}"
  []
  (let [dir (str (Files/createTempDirectory
                  "miniforge-artifact-"
                  (into-array FileAttribute [])))
        config-path (str dir "/mcp-config.json")
        artifact-path (str dir "/artifact.edn")]
    {:dir dir
     :mcp-config-path config-path
     :artifact-path artifact-path}))

;------------------------------------------------------------------------------ Layer 1
;; MCP config generation

(def ^:private mcp-server-name
  "Name used in MCP config for the artifact server.
   Must match the key in mcpServers — Claude CLI derives tool names as
   mcp__<server-name>__<tool-name>."
  "artifact")

(def ^:private mcp-tool-names
  "MCP tool names exposed by the artifact server."
  ["submit_code_artifact"
   "submit_plan"
   "submit_test_artifact"
   "submit_release_artifact"])

(defn- write-codex-mcp-config!
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

(defn- write-cursor-mcp-config!
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

(defn write-mcp-config!
  "Write MCP server configs for all supported CLI backends.

   Writes three config files:
   - <session-dir>/mcp-config.json — Claude CLI (passed via --mcp-config flag)
   - .codex/config.toml — Codex CLI (reads from CWD automatically)
   - .cursor/mcp.json — Cursor agent (reads from CWD automatically)

   Also populates :mcp-allowed-tools on the session with the fully-qualified
   tool names (mcp__artifact__<tool>) so the LLM layer can pass them to
   --allowedTools or equivalent.

   Arguments:
   - session - Session map from create-session!

   Returns: session (for threading)"
  [session]
  (let [srv-cmd (server-command (:dir session))
        {:keys [command args]} srv-cmd
        config {"mcpServers"
                {mcp-server-name
                 {"command" command
                  "args" args}}}
        allowed-tools (mapv #(str "mcp__" mcp-server-name "__" %) mcp-tool-names)
        codex-path (write-codex-mcp-config! srv-cmd)
        cursor-path (write-cursor-mcp-config! srv-cmd)]
    (spit (:mcp-config-path session) (json/generate-string config))
    (assoc session
           :mcp-allowed-tools allowed-tools
           :mcp-cleanup-files [codex-path cursor-path])))

;------------------------------------------------------------------------------ Layer 2
;; Artifact reading

(defn- uuid-str?
  "Check if a value is a UUID-shaped string."
  [v]
  (and (string? v)
       (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" v)))

(defn- instant-str?
  "Check if a value looks like an ISO instant string."
  [v]
  (and (string? v)
       (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*" v)))

(defn- key-ends-with?
  "Check if a namespaced keyword ends with the given suffix."
  [k suffix]
  (and (keyword? k)
       (str/ends-with? (name k) suffix)))

(defn- parse-uuid-strings
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
          (println "WARN: artifact file not found at" (:artifact-path session)))
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

;------------------------------------------------------------------------------ Layer 3
;; High-level session helpers

(defn- cleanup-codex-mcp-config!
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

(defn- cleanup-cursor-mcp-config!
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
   file. Cleans up the temp directory regardless of outcome.

   Usage:
     (with-artifact-session [session]
       (call-llm ... {:mcp-config (:mcp-config-path session)}))"
  [[session-sym] & body]
  `(let [session# (-> (create-session!) write-mcp-config!)
         ~session-sym session#]
     (try
       (let [result# (do ~@body)]
         {:llm-result result#
          :artifact (read-artifact session#)})
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
