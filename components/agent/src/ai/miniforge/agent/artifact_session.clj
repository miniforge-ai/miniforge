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
   [clojure.string :as str])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0
;; Session lifecycle

(defn- resolve-server-script
  "Find the MCP artifact server script path.
   Looks relative to the project root (cwd) first, then checks
   for an absolute path via MINIFORGE_HOME env var."
  []
  (let [candidates [(io/file "scripts/mcp-artifact-server.bb")
                    (when-let [home (System/getenv "MINIFORGE_HOME")]
                      (io/file home "scripts/mcp-artifact-server.bb"))]]
    (or (some #(when (and % (.exists ^java.io.File %))
                 (.getAbsolutePath ^java.io.File %))
              candidates)
        ;; Fallback: assume it's on PATH or relative
        "scripts/mcp-artifact-server.bb")))

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

(defn write-mcp-config!
  "Write the MCP server configuration JSON to the session directory.

   Arguments:
   - session - Session map from create-session!

   Returns: session (for threading)"
  [session]
  (let [script-path (resolve-server-script)
        config {"mcpServers"
                {"artifact"
                 {"command" "bb"
                  "args" [script-path "--artifact-dir" (:dir session)]}}}]
    (spit (:mcp-config-path session) (json/generate-string config))
    session))

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
   - Parsed artifact map with proper UUID types, or nil if not found"
  [session]
  (let [f (io/file (:artifact-path session))]
    (when (.exists f)
      (try
        (-> (slurp f)
            edn/read-string
            parse-uuid-strings)
        (catch Exception _
          nil)))))

;------------------------------------------------------------------------------ Layer 3
;; High-level session helpers

(defn cleanup-session!
  "Delete the temporary session directory and its contents.

   Arguments:
   - session - Session map from create-session!"
  [session]
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
