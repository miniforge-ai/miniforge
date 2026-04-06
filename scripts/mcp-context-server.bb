#!/usr/bin/env bb
;; MCP Context Server
;; ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
;;
;; Standalone MCP server (JSON-RPC 2.0 over stdin/stdout) that exposes
;; context read-through cache tools. When invoked by the Claude CLI via
;; --mcp-config, the LLM calls these tools to read files efficiently from
;; a pre-populated cache instead of issuing expensive file reads.
;;
;; Context tools:
;;   context_read  — read file content (cache hit: instant; miss: filesystem)
;;   context_grep  — search file contents by regex (cache + ripgrep fallback)
;;   context_glob  — find files by glob pattern (cache + filesystem union)
;;
;; Cache misses are recorded to context-misses.edn for meta-loop learning.
;;
;; Usage:
;;   bb scripts/mcp-context-server.bb --artifact-dir /tmp/artifact-session-xyz

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell])

;; --------------------------------------------------------------------------- CLI args

(defn parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (case (first args)
        "--artifact-dir" (recur (drop 2 args)
                                (assoc opts :artifact-dir (second args)))
        (recur (rest args) opts)))))

(def cli-opts (parse-args *command-line-args*))
(def artifact-dir (:artifact-dir cli-opts))

(when-not artifact-dir
  (binding [*out* *err*]
    (println "ERROR: --artifact-dir is required"))
  (System/exit 1))

;; --------------------------------------------------------------------------- JSON-RPC helpers

(defn write-response [id result]
  (let [msg (json/generate-string {:jsonrpc "2.0" :id id :result result})]
    (println msg)
    (flush)))

(defn write-error [id code message]
  (let [msg (json/generate-string {:jsonrpc "2.0" :id id
                                   :error {:code code :message message}})]
    (println msg)
    (flush)))

;; --------------------------------------------------------------------------- Context cache state

(def cache-state (atom {:files {} :misses []}))

(defn load-cache! []
  (let [path (str artifact-dir "/context-cache.edn")
        f (io/file path)]
    (when (.exists f)
      (try
        (let [data (edn/read-string (slurp f))]
          (swap! cache-state assoc :files (get data :files {}))
          (binding [*out* *err*]
            (println "Context cache loaded:" (count (:files data)) "files")))
        (catch Exception e
          (binding [*out* *err*]
            (println "WARN: failed to load context cache:" (ex-message e))))))))

(defn write-context-misses! []
  (let [misses (:misses @cache-state)]
    (when (seq misses)
      (let [path (str artifact-dir "/context-misses.edn")]
        (spit path (pr-str misses))
        (binding [*out* *err*]
          (println "Context misses written:" (count misses) "misses to" path))))))

;; --------------------------------------------------------------------------- Context cache helpers

(def ^:private chars-per-token 4)

(defn estimate-tokens [s]
  (if (string? s)
    (int (Math/ceil (/ (count s) chars-per-token)))
    0))

(defn apply-offset-limit [content offset limit]
  (let [lines (str/split-lines (or content ""))
        offset (or offset 0)
        sliced (drop offset lines)
        limited (if limit (take limit sliced) sliced)]
    (str/join "\n" limited)))

(defn glob-matches? [pattern path]
  (let [regex-str (-> pattern
                      (str/replace "." "\\.")
                      (str/replace "**/" "<<<GLOBSTAR>>>")
                      (str/replace "**" "<<<GLOBSTAR2>>>")
                      (str/replace "*" "[^/]*")
                      (str/replace "<<<GLOBSTAR>>>" "(.*/)?")
                      (str/replace "<<<GLOBSTAR2>>>" ".*"))
        regex (re-pattern (str "^" regex-str "$"))]
    (boolean (re-matches regex path))))

(defn grep-file [path content pattern-str]
  (try
    (let [pattern (re-pattern pattern-str)
          lines (str/split-lines content)]
      (->> lines
           (map-indexed (fn [idx line]
                          (when (re-find pattern line)
                            {:path path :line-number (inc idx) :text line})))
           (filter some?)
           vec))
    (catch Exception _e [])))

(defn format-grep-results [results]
  (->> results
       (map (fn [{:keys [path line-number text]}]
              (str path ":" line-number ":" text)))
       (str/join "\n")))

(defn shell-grep [pattern-str path-or-glob]
  (try
    (let [args (cond-> ["rg" "--no-heading" "-n" pattern-str]
                 path-or-glob (conj path-or-glob))
          {:keys [out]} (apply shell/sh args)]
      (when-not (str/blank? out)
        (->> (str/split-lines out)
             (map (fn [line]
                    (let [[file-part & rest-parts] (str/split line #":" 3)]
                      (when (>= (count rest-parts) 1)
                        {:path file-part
                         :line-number (try (Integer/parseInt (first rest-parts))
                                           (catch Exception _ 0))
                         :text (str/join ":" (rest rest-parts))}))))
             (filter some?)
             vec)))
    (catch Exception _e nil)))

(defn shell-glob [pattern]
  (try
    (let [{:keys [out]} (shell/sh "find" "." "-path" (str "./" pattern) "-type" "f")]
      (when-not (str/blank? out)
        (->> (str/split-lines out)
             (map #(str/replace-first % #"^\.\/" ""))
             (filter #(not (str/blank? %)))
             vec)))
    (catch Exception _e nil)))

(defn record-miss! [tool-name params tokens]
  (swap! cache-state update :misses conj
         (merge {:tool tool-name
                 :tokens tokens
                 :timestamp (str (java.time.Instant/now))}
                params)))

(defn cache-get [path]
  (get-in @cache-state [:files path]))

(defn cache-put! [path content]
  (swap! cache-state assoc-in [:files path] content))

(defn cached-files []
  (:files @cache-state))

(defn read-through [path]
  (or (cache-get path)
      (try
        (let [content (slurp path)]
          (cache-put! path content)
          (record-miss! "context_read" {:path path} (estimate-tokens content))
          content)
        (catch Exception _ nil))))

(defn filter-cached-files [files target-path glob-filter]
  (cond
    target-path  (select-keys files [target-path])
    glob-filter  (into {} (filter (fn [[p _]] (glob-matches? glob-filter p))) files)
    :else        files))

(defn search-cached-files [files-map pattern-str]
  (into [] (mapcat (fn [[path content]] (grep-file path content pattern-str))) files-map))

(defn cache-new-files! [results pattern-str known-files]
  (doseq [path (distinct (map :path results))
          :when (not (contains? known-files path))]
    (try
      (let [content (slurp path)]
        (cache-put! path content)
        (record-miss! "context_grep" {:path path :pattern pattern-str}
                      (estimate-tokens content)))
      (catch Exception _ nil))))

(defn grep-with-fallback [pattern-str target-path glob-filter]
  (let [files         (cached-files)
        searchable    (filter-cached-files files target-path glob-filter)
        cache-results (search-cached-files searchable pattern-str)]
    (if (seq cache-results)
      cache-results
      (let [rg-results (or (shell-grep pattern-str (or target-path glob-filter)) [])]
        (if (seq rg-results)
          (do (cache-new-files! rg-results pattern-str files)
              rg-results)
          (do (record-miss! "context_grep"
                            {:pattern pattern-str :path target-path
                             :glob glob-filter :hit false}
                            0)
              []))))))

(defn glob-with-union [pattern]
  (let [files         (cached-files)
        cache-matches (sort (filter #(glob-matches? pattern %) (keys files)))
        fs-matches    (or (shell-glob pattern) [])
        cache-set     (set cache-matches)
        new-from-fs   (remove cache-set fs-matches)]
    (when (seq new-from-fs)
      (record-miss! "context_glob"
                    {:pattern pattern :fs-only-count (count new-from-fs)}
                    0))
    (concat cache-matches new-from-fs)))

;; --------------------------------------------------------------------------- Tool handlers

(defn text-response [text]
  {:content [{:type "text" :text text}]})

(defn error-response [text]
  {:content [{:type "text" :text text}] :isError true})

(defn handle-context-read [params]
  (let [path    (get params "path")
        offset  (get params "offset")
        limit   (get params "limit")
        content (read-through path)]
    (if content
      (text-response (apply-offset-limit content offset limit))
      (error-response (str "Error reading " path ": file not found or unreadable")))))

(defn handle-context-grep [params]
  (let [results (grep-with-fallback (get params "pattern")
                                    (get params "path")
                                    (get params "glob"))]
    (text-response
      (if (seq results)
        (format-grep-results results)
        "No matches found."))))

(defn handle-context-glob [params]
  (let [matches (glob-with-union (get params "pattern"))]
    (text-response
      (if (seq matches)
        (str/join "\n" matches)
        "No files matched the pattern."))))

;; --------------------------------------------------------------------------- Tool registry

(def tool-registry
  {"context_read"
   {:tool-def
    {:name "context_read"
     :description "Read a file. Checks the pre-loaded context cache first (instant). Falls back to filesystem on cache miss. Use this instead of the Read tool."
     :inputSchema
     {:type "object"
      :required ["path"]
      :properties
      {"path"   {:type "string" :description "File path relative to project root"}
       "offset" {:type "integer" :description "Line number to start reading from (0-based)"}
       "limit"  {:type "integer" :description "Maximum number of lines to return"}}}}
    :required-params
    {"path" {:check #(and (string? %) (not (str/blank? %))) :msg "path is required"}}
    :handler handle-context-read}

   "context_grep"
   {:tool-def
    {:name "context_grep"
     :description "Search file contents for a regex pattern. Searches the pre-loaded context cache first (instant). Falls back to ripgrep on cache miss. Use this instead of the Grep tool."
     :inputSchema
     {:type "object"
      :required ["pattern"]
      :properties
      {"pattern" {:type "string" :description "Regex pattern to search for"}
       "path"    {:type "string" :description "Specific file path to search in (optional)"}
       "glob"    {:type "string" :description "Glob pattern to filter files (optional, e.g. \"*.clj\")"}}}}
    :required-params
    {"pattern" {:check #(and (string? %) (not (str/blank? %))) :msg "pattern is required"}}
    :handler handle-context-grep}

   "context_glob"
   {:tool-def
    {:name "context_glob"
     :description "Find files matching a glob pattern. Searches the pre-loaded context cache first (instant), then also checks the filesystem. Use this instead of the Glob tool."
     :inputSchema
     {:type "object"
      :required ["pattern"]
      :properties
      {"pattern" {:type "string" :description "Glob pattern to match files (e.g. \"**/*.clj\", \"src/**/*.ts\")"}}}}
    :required-params
    {"pattern" {:check #(and (string? %) (not (str/blank? %))) :msg "pattern is required"}}
    :handler handle-context-glob}})

;; --------------------------------------------------------------------------- Generic tool handler

(defn validate-required-params [required-params params]
  (doseq [[param-name {:keys [check msg]}] required-params]
    (let [v (get params param-name)]
      (when-not (and v (check v))
        (throw (ex-info msg {:code -32602}))))))

(defn handle-tool-call [tool-name arguments]
  (if-let [{:keys [required-params handler]} (get tool-registry tool-name)]
    (do
      (validate-required-params required-params arguments)
      (handler arguments))
    (throw (ex-info (str "Unknown tool: " tool-name) {:code -32601}))))

;; --------------------------------------------------------------------------- Tool definitions (derived from registry)

(def tool-definitions
  (mapv :tool-def (vals tool-registry)))

;; --------------------------------------------------------------------------- MCP dispatch

(defn handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "miniforge-context-server"
                :version "1.0.0"}})

(defn handle-tools-list [_params]
  {:tools tool-definitions})

(defn handle-tools-call [params]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (handle-tool-call tool-name arguments)))

(defn dispatch [method params]
  (case method
    "initialize"                  (handle-initialize params)
    "tools/list"                  (handle-tools-list params)
    "tools/call"                  (handle-tools-call params)
    "notifications/initialized"   nil
    "notifications/cancelled"     nil
    (throw (ex-info (str "Method not found: " method) {:code -32601}))))

;; --------------------------------------------------------------------------- Main loop

(binding [*out* *err*]
  (println "miniforge-context MCP server started, artifact-dir:" artifact-dir))

(load-cache!)

(let [reader (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
  (loop []
    (when-let [line (.readLine reader)]
      (when-not (str/blank? line)
        (try
          (let [msg (json/parse-string line)
                id (get msg "id")
                method (get msg "method")
                params (get msg "params" {})]
            (if id
              ;; Request — needs response
              (try
                (when-let [result (dispatch method params)]
                  (write-response id result))
                (catch Exception e
                  (let [code (or (:code (ex-data e)) -32603)]
                    (binding [*out* *err*]
                      (println "Error handling" method ":" (ex-message e)))
                    (write-error id code (ex-message e)))))
              ;; Notification — no response
              (try
                (dispatch method params)
                (catch Exception e
                  (binding [*out* *err*]
                    (println "Notification error:" (ex-message e)))))))
          (catch Exception e
            (binding [*out* *err*]
              (println "Parse error:" (ex-message e))))))
      (recur))))

;; Server exiting — write accumulated cache misses
(write-context-misses!)
