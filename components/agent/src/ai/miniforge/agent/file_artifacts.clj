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

(ns ai.miniforge.agent.file-artifacts
  "Fallback artifact collection from files written in the working tree."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Porcelain parsing and artifact shaping

(def ^:private artifact-manifest-name
  "Optional metadata manifest written by the agent."
  "miniforge-artifact.edn")

(defn- tracked-addition?
  "Return true when the porcelain status indicates a newly added tracked file."
  [status]
  (or (= \A (nth status 0))
      (= \A (nth status 1))))

(defn- deleted?
  "Return true when the porcelain status indicates deletion."
  [status]
  (or (= \D (nth status 0))
      (= \D (nth status 1))))

(defn- modified?
  "Return true when the porcelain status indicates a tracked file change."
  [status]
  (or (= \M (nth status 0))
      (= \M (nth status 1))
      (= \R (nth status 0))
      (= \R (nth status 1))
      (= \C (nth status 0))
      (= \C (nth status 1))
      (= \T (nth status 0))
      (= \T (nth status 1))
      (tracked-addition? status)))

(defn- porcelain-entry
  "Parse a single git status --porcelain=v1 line."
  [line]
  (when (>= (count line) 3)
    (let [status (subs line 0 2)
        raw-path (subs line 3)
        path (if (str/includes? raw-path " -> ")
               (second (str/split raw-path #" -> " 2))
               raw-path)]
    (cond
      (= status "??") {:bucket :untracked :path path}
      (deleted? status) {:bucket :deleted :path path}
      (tracked-addition? status) {:bucket :added :path path}
      (modified? status) {:bucket :modified :path path}
      :else nil))))

(defn empty-snapshot
  "Return an empty working tree snapshot."
  []
  {:untracked #{}
   :modified #{}
   :deleted #{}
   :added #{}})

(defn- add-entry
  "Add a parsed porcelain entry into the snapshot."
  [snapshot {:keys [bucket path]}]
  (update snapshot bucket conj path))

(defn- manifest-path
  "Return the manifest file path for a working dir."
  [working-dir]
  (str (io/file working-dir artifact-manifest-name)))

(defn- read-manifest
  "Read optional artifact metadata from the working directory."
  [working-dir]
  (let [path (manifest-path working-dir)
        file (io/file path)]
    (when (.exists file)
      (-> (slurp file)
          edn/read-string
          (select-keys [:code/summary :code/tests-needed?])))))

(defn- file-entry
  "Build a synthetic artifact entry for a written file."
  [working-dir action path]
  {:path path
   :content (if (= :delete action) "" (slurp (io/file working-dir path)))
   :action action})

(defn- changed-paths
  "Compute created/modified/deleted paths attributable to the current session."
  [pre post]
  (let [dirty-before (apply set/union (map #(get pre % #{})
                                           [:untracked :modified :added]))
        created (set/union
                 (set/difference (get post :untracked #{}) (get pre :untracked #{}))
                 (set/difference (get post :added #{}) (get pre :added #{})))
        modified (-> (set/difference (get post :modified #{}) (get pre :modified #{}))
                     (set/difference dirty-before)
                     (set/difference created))
        deleted (-> (set/difference (get post :deleted #{}) (get pre :deleted #{}))
                    (set/difference dirty-before))]
    {:create (disj created artifact-manifest-name)
     :modify (disj modified artifact-manifest-name)
     :delete (disj deleted artifact-manifest-name)}))

(defn- synthetic-artifact
  "Build a synthetic code artifact from written file sets."
  [working-dir changed]
  (let [files (->> [[:create (:create changed)]
                    [:modify (:modify changed)]
                    [:delete (:delete changed)]]
                   (mapcat (fn [[action paths]]
                             (map #(file-entry working-dir action %) (sort paths))))
                   vec)]
    (when (seq files)
      (merge {:code/files files
              :code/summary (str (count files)
                                 " files collected from agent working directory (no MCP submit)")
              :code/language "clojure"
              :code/tests-needed? true}
             (read-manifest working-dir)))))

;------------------------------------------------------------------------------ Layer 1
;; Working tree snapshot and fallback artifact collection

(defn snapshot-working-dir
  "Capture the current git dirty state for a working directory.

   Returns sets of paths relative to working-dir. Paths already staged
   as new files are tracked in :added so they can still be treated as
   :create during fallback artifact synthesis.

   Returns:
   - a snapshot map `{:untracked :modified :deleted :added}` on
     success
   - a `:fault` anomaly when `git status` returns a non-zero exit
     code (carrying `:working-dir`, `:exit`, `:stderr`)

   This is the canonical, anomaly-returning entry point. The single
   internal caller (`collect-written-files`) branches on
   `anomaly/anomaly?` and falls through to `nil` so a missing or
   broken git repo never aborts the surrounding review phase."
  [working-dir]
  (let [{:keys [exit out err]} (shell/sh "git" "-C" working-dir
                                         "status" "--porcelain=v1"
                                         "--untracked-files=all"
                                         "--ignored=no" "--" ".")]
    (if (zero? exit)
      (reduce add-entry
              (empty-snapshot)
              (keep porcelain-entry (str/split-lines out)))
      (anomaly/anomaly :fault
                       "Failed to snapshot working directory"
                       {:working-dir working-dir
                        :exit exit
                        :stderr err}))))

(defn snapshot-via-executor
  "Capture git dirty state inside a capsule via the executor.

   Like snapshot-working-dir but runs git status through the executor
   instead of shelling out locally."
  [exec! executor env-id working-dir]
  (let [result (exec! executor env-id
                      "git status --porcelain=v1 --untracked-files=all --ignored=no -- ."
                      {:workdir working-dir})]
    (if (and (:ok? result) (zero? (get-in result [:data :exit-code] 1)))
      (reduce add-entry
              (empty-snapshot)
              (keep porcelain-entry (str/split-lines (get-in result [:data :stdout] ""))))
      (empty-snapshot))))

(defn collect-written-files
  "Collect files written during the agent session into a synthetic code artifact.

   Uses the pre-session snapshot to exclude files that were already dirty before
   the session started.

   `snapshot-working-dir` is anomaly-returning; we treat any anomaly
   the same way we treat a thrown exception — return nil so a broken
   working tree never aborts the review phase. The `try/catch` covers
   non-anomaly throws from downstream IO (e.g. `synthetic-artifact`
   reading file content)."
  [pre-snapshot working-dir]
  (when pre-snapshot
    (try
      (let [snap (snapshot-working-dir working-dir)]
        (when-not (anomaly/anomaly? snap)
          (->> snap
               (changed-paths pre-snapshot)
               (synthetic-artifact working-dir))))
      (catch Exception _
        nil))))

(defn- safe-resolve
  "Resolve `path` under `working-dir` defensively. Returns a regular-file
   `java.io.File` when:
   - `path` is non-blank
   - the canonical target stays inside `working-dir` (rejects absolute
     paths, `..` escapes, and symlinks pointing outside)
   - the target exists and is a regular file (not a directory)
   Returns nil otherwise. Paths come from agent-produced artifacts, so
   this is a real boundary check, not paranoia."
  [working-dir path]
  (try
    (when (and (string? path) (not (str/blank? path)))
      (let [base   (.getCanonicalFile (io/file working-dir))
            target (.getCanonicalFile (io/file working-dir path))]
        (when (and (.exists target)
                   (.isFile target)
                   (.startsWith (.toPath target) (.toPath base)))
          target)))
    (catch java.io.IOException _ nil)
    (catch SecurityException _ nil)
    (catch IllegalArgumentException _ nil)))

(defn- read-rehydrated-file
  "Read one rehydration entry. Returns the file map or nil for any
   failure (path traversal, missing/non-regular file, permissions,
   read errors). Skipping unreadable paths is preferable to bubbling
   an exception that fails the entire review phase."
  [working-dir path action]
  (if (= :delete action)
    {:path path :content "" :action :delete}
    (when-let [file (safe-resolve working-dir path)]
      (try
        {:path path :content (slurp file) :action action}
        (catch java.io.IOException _ nil)
        (catch SecurityException _ nil)))))

(defn- rehydration-entry
  "Build one `:code/files` entry for `path` under `working-dir`,
   honoring the action recorded in `action-by-path` (defaulting to
   `:modify`). Named so callers can pass it via `partial` instead of
   hoisting the closure into an inline `fn`."
  [working-dir action-by-path path]
  (read-rehydrated-file working-dir
                        path
                        (get action-by-path path :modify)))

(defn rehydrate-files
  "Read file content from `working-dir` for each path in `paths`,
   producing the `:code/files` vector callers expect.

   Used when an upstream phase persisted only paths-as-metadata
   (`:code/file-paths` / `:code/file-actions` from
   `lightweight-curated-artifact`) and a downstream phase needs the
   content reconstituted. After the implement phase boundary persists
   dirty work as a commit on the task branch, the worktree is clean
   at HEAD — `git status --porcelain` returns empty and the existing
   `collect-written-files` fallback yields no content. This function
   reads the still-on-disk content directly so the reviewer (and any
   later phase) can see what landed.

   `paths` is the sequence of file paths to read (relative to
   `working-dir`). `actions` is the parallel sequence of action
   keywords (`:create` / `:modify` / `:delete`); when omitted, every
   present path is treated as `:modify`. For `:delete` actions the
   content is the empty string.

   Paths that fail validation (absolute, `..` escape, symlink-out,
   directory at path), do not exist, or are unreadable for any reason
   are skipped — better to under-report than to invent content or
   fail a downstream phase on a stray path produced by an agent.

   Returns a vector of `{:path :content :action}` maps, possibly
   empty. Returns nil when `working-dir` is nil."
  ([working-dir paths]
   (rehydrate-files working-dir paths nil))
  ([working-dir paths actions]
   (when working-dir
     (let [action-by-path (zipmap paths (or actions (repeat :modify)))]
       (->> paths
            (keep (partial rehydration-entry working-dir action-by-path))
            vec)))))

(defn- read-file-via-executor
  "Read a file's content from the capsule. Returns empty string on failure."
  [exec! executor env-id working-dir path]
  (let [r (exec! executor env-id (str "cat " (pr-str path)) {:workdir working-dir})]
    (get-in r [:data :stdout] "")))

(defn- read-manifest-via-executor
  "Read the artifact manifest from the capsule, if present."
  [exec! executor env-id working-dir]
  (let [r (exec! executor env-id
                 (str "cat " (pr-str artifact-manifest-name))
                 {:workdir working-dir})]
    (when (and (:ok? r) (zero? (get-in r [:data :exit-code] 1)))
      (try (-> (get-in r [:data :stdout])
               edn/read-string
               (select-keys [:code/summary :code/tests-needed?]))
           (catch Exception _ nil)))))

(defn- changed-path->file-entry
  "Build a file entry map for a changed path, reading content via executor."
  [exec! executor env-id working-dir action path]
  {:path path
   :content (if (= :delete action)
              ""
              (read-file-via-executor exec! executor env-id working-dir path))
   :action action})

(defn- changed-paths->file-entries
  "Convert changed-paths result to a flat vector of file entry maps."
  [changed exec! executor env-id working-dir]
  (->> [[:create (:create changed)]
        [:modify (:modify changed)]
        [:delete (:delete changed)]]
       (mapcat (fn [[action paths]]
                 (map #(changed-path->file-entry exec! executor env-id working-dir action %)
                      (sort paths))))
       vec))

(defn collect-written-files-via-executor
  "Like collect-written-files but runs git status inside a capsule via executor.
   Reads file contents from the capsule for the synthetic artifact."
  [pre-snapshot exec! executor env-id working-dir]
  (when pre-snapshot
    (try
      (let [changed (changed-paths pre-snapshot
                                   (snapshot-via-executor exec! executor env-id working-dir))
            files (changed-paths->file-entries changed exec! executor env-id working-dir)]
        (when (seq files)
          (merge {:code/files files
                  :code/summary (str (count files)
                                     " files collected from capsule working directory (no MCP submit)")
                  :code/tests-needed? true}
                 (read-manifest-via-executor exec! executor env-id working-dir))))
      (catch Exception _
        nil))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (snapshot-working-dir (System/getProperty "user.dir"))

  (collect-written-files (snapshot-working-dir (System/getProperty "user.dir"))
                         (System/getProperty "user.dir"))

  :leave-this-here)
