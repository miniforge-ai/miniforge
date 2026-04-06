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

(ns ai.miniforge.mcp-context-server.context-cache
  "Read-through context cache for MCP file exploration tools.

   Provides context_read, context_grep, and context_glob tool handlers that
   serve content from a pre-loaded cache (instant) with filesystem fallback.
   Cache misses are recorded for meta-loop learning.

   Layer 0: Pure helpers (token estimation, line slicing, glob matching, grep)
   Layer 1: Stateful cache operations and tool handler functions"
  (:require [ai.miniforge.mcp-context-server.messages :as msg]
            [clojure.edn :as edn]
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
                      (str/replace "**/" "<<<GLOBSTAR>>>")
                      (str/replace "**" "<<<GLOBSTAR2>>>")
                      (str/replace "*" "[^/]*")
                      (str/replace "<<<GLOBSTAR>>>" "(.*/)?")
                      (str/replace "<<<GLOBSTAR2>>>" ".*"))
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
            (println (msg/t :cache/loaded {:count (count (:files data))}))))
        (catch Exception e
          (binding [*out* *err*]
            (println (msg/t :cache/load-failed {:error (ex-message e)}))))))))

(defn flush-misses!
  "Write accumulated cache misses to context-misses.edn in artifact-dir."
  [artifact-dir]
  (let [misses (:misses @cache-state)]
    (when (seq misses)
      (let [path (str artifact-dir "/context-misses.edn")]
        (spit path (pr-str misses))
        (binding [*out* *err*]
          (println (msg/t :cache/misses-written {:count (count misses) :path path})))))))

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
;; Read-through cache: resolve content from cache or filesystem

(defn- read-through
  "Resolve file content: cache hit returns instantly, miss reads from disk,
   caches the content, and records the miss. Returns content string or nil."
  [path]
  (or (cache-get path)
      (try
        (let [content (slurp path)]
          (cache-put! path content)
          (record-miss! "context_read" {:path path} (estimate-tokens content))
          content)
        (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 1
;; MCP response helpers

(defn- text-response
  "Wrap a string in the MCP tool response shape."
  [text]
  {:content [{:type "text" :text text}]})

(defn- error-response
  "Wrap an error message in the MCP tool error shape."
  [text]
  {:content [{:type "text" :text text}] :isError true})

(defn- grep-response
  "Build MCP response from grep results. Returns formatted matches or
   a no-matches message when empty."
  [results]
  (text-response
    (if (seq results)
      (format-grep-results results)
      (msg/t :context/no-grep-matches))))

;------------------------------------------------------------------------------ Layer 1
;; Grep helpers: file selection and fallback

(defn filter-cached-files
  "Select which cached files to search based on path or glob filter.
   Returns a {path → content} map."
  [files target-path glob-filter]
  (cond
    target-path  (select-keys files [target-path])
    glob-filter  (into {} (filter (fn [[p _]] (glob-matches? glob-filter p))) files)
    :else        files))

(defn- search-cached-files
  "Grep across a map of {path → content} for a pattern.
   Returns a flat vector of grep result maps."
  [files-map pattern-str]
  (into [] (mapcat (fn [[path content]] (grep-file path content pattern-str))) files-map))

(defn- cache-new-files!
  "Cache files discovered by shell grep that aren't already cached.
   Records a miss for each newly cached file."
  [results pattern-str known-files]
  (doseq [path (distinct (map :path results))
          :when (not (contains? known-files path))]
    (try
      (let [content (slurp path)]
        (cache-put! path content)
        (record-miss! "context_grep" {:path path :pattern pattern-str}
                      (estimate-tokens content)))
      (catch Exception _ nil))))

(defn- grep-with-fallback
  "Search cached files first. On cache miss, fall back to ripgrep,
   cache any newly discovered files, and record misses."
  [pattern-str target-path glob-filter]
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

;------------------------------------------------------------------------------ Layer 1
;; Glob helpers

(defn- glob-with-union
  "Match cached paths, union with filesystem glob, record misses for
   files found only on disk. Returns a sequence of matched paths."
  [pattern]
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

;------------------------------------------------------------------------------ Layer 2
;; Tool handler functions (compose Layer 0 + Layer 1)

(defn handle-context-read
  "Handler for context_read MCP tool."
  [params]
  (let [path    (get params "path")
        offset  (get params "offset")
        limit   (get params "limit")
        content (read-through path)]
    (if content
      (text-response (apply-offset-limit content offset limit))
      (error-response (msg/t :context/read-error {:path path})))))

(defn handle-context-grep
  "Handler for context_grep MCP tool."
  [params]
  (grep-response
    (grep-with-fallback (get params "pattern")
                        (get params "path")
                        (get params "glob"))))

(defn handle-context-glob
  "Handler for context_glob MCP tool."
  [params]
  (let [matches (glob-with-union (get params "pattern"))]
    (text-response
      (if (seq matches)
        (str/join "\n" matches)
        (msg/t :context/no-glob-matches)))))
