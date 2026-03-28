(ns ai.miniforge.mcp-artifact-server.context-cache
  "Read-through context cache for MCP file exploration tools.

   Provides context_read, context_grep, and context_glob tool handlers that
   serve content from a pre-loaded cache (instant) with filesystem fallback.
   Cache misses are recorded for meta-loop learning.

   Layer 0: Pure helpers (token estimation, line slicing, glob matching, grep)
   Layer 1: Stateful cache operations and tool handler functions"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(def ^:private chars-per-token
  "Approximate characters per token for estimation."
  4)

(defn estimate-tokens
  "Estimate token count from a string (~4 chars per token)."
  [s]
  (if (string? s)
    (int (Math/ceil (/ (count s) chars-per-token)))
    0))

(defn apply-offset-limit
  "Apply line-based offset and limit to content string.
   offset is 0-based line index; limit is max lines to return."
  [content offset limit]
  (let [lines (str/split-lines (or content ""))
        offset (or offset 0)
        sliced (drop offset lines)
        limited (if limit (take limit sliced) sliced)]
    (str/join "\n" limited)))

(defn glob-matches?
  "Check if a path matches a glob pattern.
   Supports * (single segment) and ** (any depth)."
  [pattern path]
  (let [regex-str (-> pattern
                      (str/replace "." "\\.")
                      (str/replace "**" "<<<GLOBSTAR>>>")
                      (str/replace "*" "[^/]*")
                      (str/replace "<<<GLOBSTAR>>>" ".*"))
        regex (re-pattern (str "^" regex-str "$"))]
    (boolean (re-matches regex path))))

(defn grep-file
  "Search a file's content for a regex pattern.
   Returns vector of {:path :line-number :text} for matching lines."
  [path content pattern-str]
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

(defn format-grep-results
  "Format grep results as path:line:text lines."
  [results]
  (->> results
       (map (fn [{:keys [path line-number text]}]
              (str path ":" line-number ":" text)))
       (str/join "\n")))

;------------------------------------------------------------------------------ Layer 0
;; Shell fallbacks

(defn shell-grep
  "Fall back to ripgrep for files not in cache.
   Returns vector of {:path :line-number :text} or nil."
  [pattern-str path-or-glob]
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

(defn shell-glob
  "Fall back to filesystem glob via find.
   Returns vector of relative file paths or nil."
  [pattern]
  (try
    (let [{:keys [out]} (shell/sh "find" "." "-path" (str "./" pattern) "-type" "f")]
      (when-not (str/blank? out)
        (->> (str/split-lines out)
             (map #(str/replace-first % #"^\.\/" ""))
             (filter #(not (str/blank? %)))
             vec)))
    (catch Exception _e nil)))

;------------------------------------------------------------------------------ Layer 1
;; Stateful cache operations

;; Cache atom holding :files (path → content) and :misses (recorded cache misses).
(defonce cache-state
  (atom {:files {} :misses []}))

(defn reset-state!
  "Reset cache state. Intended for test isolation."
  []
  (reset! cache-state {:files {} :misses []}))

(defn load-cache!
  "Load context-cache.edn from artifact-dir into the cache atom."
  [artifact-dir]
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

(defn flush-misses!
  "Write accumulated cache misses to context-misses.edn in artifact-dir."
  [artifact-dir]
  (let [misses (:misses @cache-state)]
    (when (seq misses)
      (let [path (str artifact-dir "/context-misses.edn")]
        (spit path (pr-str misses))
        (binding [*out* *err*]
          (println "Context misses written:" (count misses) "misses to" path))))))

(defn- record-miss!
  "Record a cache miss for meta-loop learning."
  [tool-name params tokens]
  (swap! cache-state update :misses conj
         (merge {:tool tool-name
                 :tokens tokens
                 :timestamp (str (java.time.Instant/now))}
                params)))

(defn- cache-get
  "Get cached content for a path, or nil if not cached."
  [path]
  (get-in @cache-state [:files path]))

(defn- cache-put!
  "Store content in the cache for a path."
  [path content]
  (swap! cache-state assoc-in [:files path] content))

(defn- cached-files
  "Return the current files map from the cache."
  []
  (:files @cache-state))

;------------------------------------------------------------------------------ Layer 1
;; Tool handler functions

(defn handle-context-read
  "Handler for context_read MCP tool.
   Cache hit → return content. Miss → read from filesystem, cache, record miss."
  [params]
  (let [path   (get params "path")
        offset (get params "offset")
        limit  (get params "limit")
        cached (cache-get path)]
    (if cached
      {:content [{:type "text" :text (apply-offset-limit cached offset limit)}]}
      (try
        (let [content (slurp path)
              tokens  (estimate-tokens content)]
          (cache-put! path content)
          (record-miss! "context_read" {:path path} tokens)
          {:content [{:type "text" :text (apply-offset-limit content offset limit)}]})
        (catch Exception e
          {:content [{:type "text"
                      :text (str "Error reading " path ": " (ex-message e))}]
           :isError true})))))

(defn handle-context-grep
  "Handler for context_grep MCP tool.
   Searches cached files first, falls back to ripgrep on cache miss."
  [params]
  (let [pattern-str (get params "pattern")
        target-path (get params "path")
        glob-filter (get params "glob")
        files       (cached-files)
        files-to-search (cond
                          target-path
                          (if-let [content (get files target-path)]
                            {target-path content}
                            {})

                          glob-filter
                          (into {} (filter (fn [[p _]] (glob-matches? glob-filter p)) files))

                          :else files)
        cache-results (->> files-to-search
                           (mapcat (fn [[path content]]
                                     (grep-file path content pattern-str)))
                           vec)]
    (if (seq cache-results)
      {:content [{:type "text" :text (format-grep-results cache-results)}]}
      (let [rg-results (shell-grep pattern-str (or target-path glob-filter))]
        (when (seq rg-results)
          (let [paths (distinct (map :path rg-results))]
            (doseq [p paths]
              (when-not (get files p)
                (try
                  (let [content (slurp p)]
                    (cache-put! p content)
                    (record-miss! "context_grep" {:path p :pattern pattern-str}
                                  (estimate-tokens content)))
                  (catch Exception _ nil))))))
        (when (empty? rg-results)
          (record-miss! "context_grep"
                        {:pattern pattern-str :path target-path :glob glob-filter :hit false}
                        0))
        {:content [{:type "text"
                    :text (if (seq rg-results)
                            (format-grep-results rg-results)
                            "No matches found.")}]}))))

(defn handle-context-glob
  "Handler for context_glob MCP tool.
   Matches cached paths first, unions with filesystem glob results."
  [params]
  (let [pattern       (get params "pattern")
        files         (cached-files)
        cache-matches (->> (keys files)
                           (filter #(glob-matches? pattern %))
                           sort
                           vec)
        fs-matches    (or (shell-glob pattern) [])
        cache-set     (set cache-matches)
        new-from-fs   (remove cache-set fs-matches)
        all-matches   (concat cache-matches new-from-fs)]
    (when (seq new-from-fs)
      (record-miss! "context_glob"
                    {:pattern pattern :fs-only-count (count new-from-fs)}
                    0))
    (if (seq all-matches)
      {:content [{:type "text" :text (str/join "\n" all-matches)}]}
      {:content [{:type "text" :text "No files matched the pattern."}]})))
