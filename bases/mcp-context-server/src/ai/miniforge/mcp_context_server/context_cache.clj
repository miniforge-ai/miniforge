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

(declare source-root)
(declare file-mtime)

(defn shell-grep
  "Fall back to ripgrep for files not in cache.
   Returns vector of {:path :line-number :text} or nil."
  [pattern-str path-or-glob]
  (try
    (let [args (cond-> ["rg" "--no-heading" "-n" pattern-str]
                 path-or-glob (conj path-or-glob))
          {:keys [out]} (apply shell/sh (concat args [:dir (source-root)]))]
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
  "Fall back to filesystem glob by walking source-root.
   Returns vector of relative file paths or nil."
  [pattern]
  (try
    (let [root (io/file (source-root))
          root-path (.toPath (.getAbsoluteFile root))
          matches (->> (tree-seq (fn [^java.io.File file]
                                   (and (.isDirectory file)
                                        (not= ".git" (.getName file))))
                                 #(.listFiles ^java.io.File %)
                                 root)
                       rest
                       (filter #(.isFile ^java.io.File %))
                       (map (fn [^java.io.File file]
                              (-> root-path
                                  (.relativize (.toPath (.getAbsoluteFile file)))
                                  str)))
                       (filter #(glob-matches? pattern %))
                       sort
                       vec)]
      (when (seq matches)
        matches))
    (catch Exception _e nil)))

;------------------------------------------------------------------------------ Layer 1
;; Stateful cache operations

;; Cache atom holding :files (path → content), :mtimes (path → epoch-ms of the
;; file when its content was cached, for write-invalidation), and :misses
;; (recorded cache misses).
(defonce cache-state
  (atom {:files {} :mtimes {} :misses [] :source-root nil :artifact-dir nil :workdir nil}))

(defn reset-state!
  "Reset cache state. Intended for test isolation."
  []
  (reset! cache-state {:files {} :mtimes {} :misses [] :source-root nil :artifact-dir nil :workdir nil}))

(defn set-workdir!
  "Set the write target for context_write — the agent's worktree, distinct from
   `:source-root` (the read reference). Reads resolve against source-root;
   writes land in the worktree where the run collects the diff. Agent-agnostic:
   every backend's context server is launched with the same command, so this
   applies to Claude/Codex/Cursor alike."
  [workdir]
  (swap! cache-state assoc :workdir workdir))

(defn- source-root
  []
  (or (:source-root @cache-state) "."))

(defn- write-root
  "Root for context_write: the worktree (`:workdir`) when set, else source-root."
  []
  (or (:workdir @cache-state) (source-root)))

(defn- resolve-source-path
  [path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (.getPath f)
      (.getPath (io/file (source-root) path)))))

(defn- resolve-write-path
  "Resolve a write path against write-root (the worktree)."
  [path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (.getPath f)
      (.getPath (io/file (write-root) path)))))

(def ^:private persistent-cache-relpath ".miniforge/context-cache.edn")

(defn- persistent-cache-path
  "Worktree-resident cross-phase cache file, or nil when there's no
   source-root. Under .miniforge/ (gitignored) so it rides workspace
   persistence into containers across phases."
  [source-root]
  (when-not (str/blank? source-root)
    (str source-root "/" persistent-cache-relpath)))

(defn- read-cache-edn
  "Read {:files .. :mtimes ..} from an EDN cache file, or nil on missing/bad."
  [path]
  (let [f (and path (io/file path))]
    (when (and f (.exists f))
      (try (edn/read-string (slurp f))
           (catch Exception _ nil)))))

(defn load-cache!
  "Load the context cache from two sources (freshest wins):

   1. the cross-phase PERSISTENT cache at <source-root>/.miniforge/
      context-cache.edn — reads accumulated by earlier phases. Its stored
      mtimes are restored, so any file changed since it was persisted is
      detected as stale and re-read on access (no stale cross-phase content).
   2. this phase's PRE-POPULATED cache at <artifact-dir>/context-cache.edn —
      the orchestrator's curated, just-read file set. Merged ON TOP (wins)
      and stamped with current disk mtimes."
  ([artifact-dir] (load-cache! artifact-dir nil))
  ([artifact-dir source-root]
   (swap! cache-state assoc :source-root source-root :artifact-dir artifact-dir)
   (try
     (let [persistent (read-cache-edn (persistent-cache-path source-root))
           prepop     (read-cache-edn (str artifact-dir "/context-cache.edn"))
           pre-files  (get prepop :files {})
           files      (merge (get persistent :files {}) pre-files)
           ;; persistent entries keep stored mtimes (stale ones re-read);
           ;; pre-pop entries were just read fresh → stamp current mtime.
           pre-mtimes (reduce-kv (fn [m p _] (assoc m p (file-mtime p))) {} pre-files)
           mtimes     (merge (get persistent :mtimes {}) pre-mtimes)]
       (swap! cache-state assoc :files files :mtimes mtimes)
       (when (seq files)
         (binding [*out* *err*]
           (println (msg/t :cache/loaded {:count (count files)})))))
     (catch Exception e
       (binding [*out* *err*]
         (println (msg/t :cache/load-failed {:error (ex-message e)})))))))

(defn save-cache!
  "Persist the accumulated in-memory cache (pre-populated + read-through
   additions, with mtimes) to the worktree-resident cross-phase cache file so
   the NEXT phase's server loads it. No-ops without a source-root or when
   nothing is cached. Best-effort: a write failure is logged, not thrown."
  []
  (let [{:keys [source-root files mtimes]} @cache-state]
    (when-let [path (and (seq files) (persistent-cache-path source-root))]
      (try
        (io/make-parents path)
        (spit path (pr-str {:files files :mtimes mtimes}))
        (binding [*out* *err*]
          (println (msg/t :cache/saved {:count (count files) :path path})))
        (catch Exception e
          (binding [*out* *err*]
            (println (msg/t :cache/save-failed {:error (ex-message e)}))))))))

(defn flush-misses!
  "Write accumulated cache misses to context-misses.edn in artifact-dir."
  [artifact-dir]
  (let [misses (:misses @cache-state)]
    (when (seq misses)
      (let [path (str artifact-dir "/context-misses.edn")]
        (spit path (pr-str misses))
        (binding [*out* *err*]
          (println (msg/t :cache/misses-written {:count (count misses) :path path})))))))

;; `handle-submit` (the artifact.edn metadata channel) removed: the artifact is
;; the worktree/container diff (promotion); the curator derives the summary.
;; Models ignored the opt-in submit tool anyway. See artifact_session/mcp-tools.

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

(defn- file-mtime
  "Last-modified epoch-ms of the resolved source file, or nil if absent."
  [path]
  (let [f (io/file (resolve-source-path path))]
    (when (.exists f)
      (.lastModified f))))

(defn- cache-put!
  "Store content in the cache for a path, stamping the file's current mtime so
   `cache-stale?` can later detect on-disk changes (write-invalidation)."
  [path content]
  (swap! cache-state #(-> %
                          (assoc-in [:files path] content)
                          (assoc-in [:mtimes path] (file-mtime path)))))

(defn- cache-stale?
  "True only when the cached entry can be PROVEN out of date: we recorded an
   mtime for it, the file still exists, and it was modified after we cached.
   When there's no recorded mtime or no on-disk file, staleness cannot be
   proven, so the cached content is trusted (preserves orchestrator-seeded /
   pre-pop entries and virtual paths). Every entry cached via `cache-put!`
   carries an mtime, so read-after-write — an agent writing a file it earlier
   read through the cache — is correctly detected and re-read."
  [path]
  (let [cached (get-in @cache-state [:mtimes path])
        disk   (file-mtime path)]
    (boolean (and cached disk (> disk cached)))))

(defn- cached-files
  "Return the current files map from the cache."
  []
  (:files @cache-state))

;------------------------------------------------------------------------------ Layer 1
;; Read-through cache: resolve content from cache or filesystem

(defn- read-through
  "Resolve file content. Fresh cache hit returns instantly. A miss — or a
   cached entry whose file changed on disk since it was cached — reads from
   disk, re-caches (with the new mtime), and returns the current content.
   Records a miss only on a GENUINE miss (never cached), not on a
   staleness-driven refresh. Returns content string or nil."
  [path]
  (let [cached (cache-get path)]
    (if (and cached (not (cache-stale? path)))
      cached
      (try
        (let [content (slurp (resolve-source-path path))]
          (cache-put! path content)
          (when (nil? cached)
            (record-miss! "context_read" {:path path} (estimate-tokens content)))
          content)
        (catch Exception _ nil)))))

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
      (let [content (slurp (resolve-source-path path))]
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

(defn handle-context-write
  "Handler for the context_write MCP tool. Writes the full file content to the
   worktree (write-root) and refreshes the cache so a later context_read returns
   the new content. Agent-agnostic edit path — works for any MCP client and,
   unlike native Write/Edit, needs no prior native Read of the file."
  [params]
  (let [path    (get params "path")
        content (get params "content")]
    (if (or (str/blank? path) (not (string? content)))
      (error-response (msg/t :context/write-error {:path (str path)}))
      (try
        (let [target (io/file (resolve-write-path path))]
          (io/make-parents target)
          (spit target content)
          ;; Cache the written content stamped with the new file's mtime so
          ;; read-through serves this write — cache-stale? trusts a freshly
          ;; stamped entry over an older/absent source-root copy.
          (swap! cache-state #(-> %
                                  (assoc-in [:files path] content)
                                  (assoc-in [:mtimes path] (.lastModified target))))
          (text-response (msg/t :context/write-ok {:path path :bytes (count content)})))
        (catch Exception e
          (error-response (msg/t :context/write-failed
                                 {:path path :error (ex-message e)})))))))
