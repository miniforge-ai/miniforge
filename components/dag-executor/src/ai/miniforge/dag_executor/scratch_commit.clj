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

(ns ai.miniforge.dag-executor.scratch-commit
  "Lightweight file persistence to git scratch refs via object-store plumbing.

   Writes single-file snapshots into refs/miniforge/scratch/<workflow-id>
   using only git plumbing commands — hash-object, mktree, commit-tree,
   update-ref.  Never touches the parent repo's working tree or index.

   Layer 0: scratch-ref-name      pure, no I/O
   Layer 1: private git helpers
   Layer 2: scratch-commit!       <200 ms, object-store only
             list-scratch-commits  reads commit log
             gc-scratch-refs!      deletes refs older than max-age-days"
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.result :as result])
  (:import
   [java.io File]))

;;------------------------------------------------------------------------------ Layer 0
;; Pure helper

(defn scratch-ref-name
  "Return the fully-qualified scratch ref name for a workflow.

   Example:
     (scratch-ref-name \"wf-abc123\")
     ;=> \"refs/miniforge/scratch/wf-abc123\""
  [workflow-id]
  (str "refs/miniforge/scratch/" workflow-id))

;;------------------------------------------------------------------------------ Layer 1
;; Private git-plumbing helpers

;; ASCII unit-separator (U+001F) used as field delimiter in git --format
;; strings.  This control character never appears in SHAs, timestamps, ref
;; names, or typical commit messages, making it safe to split on.
(def ^:private ^String sep (str (char 0x1F)))
(def ^:private sep-pattern (re-pattern sep))

(defn- sh-git
  "Run a git command with CWD set to `dir`.
   Returns the standard {:exit int :out str :err str} shell map.
   Catches process-launch exceptions and normalises them to {:exit 1}."
  [dir & args]
  (try
    (apply shell/sh "git" "-C" dir args)
    (catch Exception e
      {:exit 1 :out "" :err (.getMessage e)})))

(defn- sh-git-in
  "Like sh-git but pipes `stdin` (a String) to the git process."
  [dir stdin & args]
  (try
    (apply shell/sh "git" "-C" dir (concat args [:in stdin]))
    (catch Exception e
      {:exit 1 :out "" :err (.getMessage e)})))

(defn- git-ok?
  "True when a shell result has exit code 0."
  [{:keys [exit]}]
  (zero? (or exit 1)))

(defn- out-trim
  "Return trimmed stdout from a shell result."
  [{:keys [out]}]
  (str/trim (or out "")))

;;------------------------------------------------------------------------------ Layer 2
;; Core operations

(defn scratch-commit!
  "Snapshot `file-path` into the workflow's scratch ref without touching
   the parent repo's working tree or index.

   Sequence of git plumbing calls (all scoped to `parent-repo-path`):
     1. git hash-object -w <abs-file-path>              -> blob SHA
     2. git mktree   (stdin: single tree-entry line)    -> tree SHA
     3. git commit-tree <tree> [-p <tip>] -m 'scratch: ...' -> commit SHA
     4. git update-ref refs/miniforge/scratch/<id> <sha>

   Arguments:
   - parent-repo-path  Absolute path to the parent git repository
   - workflow-id       Workflow identifier used as the ref suffix
   - phase             Free-form phase label (e.g. \"implement\", \"verify\")
   - file-path         Path to the file to snapshot (absolute or relative)

   Returns:
     result/ok  {:commit-sha str :ref str :workflow-id str :phase str :file-path str}
     result/err with :code :scratch-commit/<reason> on any git failure"
  [parent-repo-path workflow-id phase file-path]
  (try
    (let [ref-name (scratch-ref-name workflow-id)
          abs-path (.getAbsolutePath (File. ^String file-path))

          ;; Step 1: hash the file content into the object store — no index touched.
          blob-r   (sh-git parent-repo-path "hash-object" "-w" abs-path)]
      (if-not (git-ok? blob-r)
        (result/err :scratch-commit/hash-object-failed
                    (str "hash-object failed for " file-path ": " (:err blob-r))
                    {:file-path file-path :stderr (:err blob-r)})

        (let [blob-sha   (out-trim blob-r)
              file-name  (.getName (File. ^String file-path))
              ;; Step 2: build a minimal single-entry tree without touching the index.
              ;;   Tree-entry format: "<mode> blob <sha>\t<name>"
              tree-entry (str "100644 blob " blob-sha "\t" file-name)
              tree-r     (sh-git-in parent-repo-path tree-entry "mktree")]
          (if-not (git-ok? tree-r)
            (result/err :scratch-commit/mktree-failed
                        (str "mktree failed: " (:err tree-r))
                        {:stderr (:err tree-r)})

            (let [tree-sha   (out-trim tree-r)
                  ;; Step 3a: resolve the current scratch-ref tip (optional parent).
                  parent-r   (sh-git parent-repo-path "rev-parse" "--verify" ref-name)
                  parent-sha (when (git-ok? parent-r) (out-trim parent-r))
                  commit-msg (str "scratch: " workflow-id " " phase " " file-path)
                  ;; Step 3b: build commit-tree arg list; -p precedes -m.
                  ct-args    (-> ["commit-tree" tree-sha]
                                 (cond-> parent-sha (into ["-p" parent-sha]))
                                 (into ["-m" commit-msg]))
                  commit-r   (apply sh-git parent-repo-path ct-args)]
              (if-not (git-ok? commit-r)
                (result/err :scratch-commit/commit-tree-failed
                            (str "commit-tree failed: " (:err commit-r))
                            {:tree-sha tree-sha :stderr (:err commit-r)})

                (let [commit-sha (out-trim commit-r)
                      ;; Step 4: atomically advance the scratch ref.
                      ref-r      (sh-git parent-repo-path
                                         "update-ref" ref-name commit-sha)]
                  (if-not (git-ok? ref-r)
                    (result/err :scratch-commit/update-ref-failed
                                (str "update-ref failed: " (:err ref-r))
                                {:ref ref-name :stderr (:err ref-r)})
                    (result/ok {:commit-sha  commit-sha
                                :ref         ref-name
                                :workflow-id workflow-id
                                :phase       phase
                                :file-path   file-path})))))))))

    (catch Exception e
      (result/err :scratch-commit/unexpected
                  (str "unexpected error in scratch-commit!: " (.getMessage e))))))

(defn list-scratch-commits
  "Return a vector of commit-metadata maps for `workflow-id`'s scratch ref,
   newest first (standard git-log order).

   Each map contains:
     :commit-sha      (string)  Full 40-char SHA
     :message         (string)  Commit subject line
     :timestamp-epoch (long)    Author-date as seconds since epoch
     :workflow-id     (string)  Echoed workflow-id for caller convenience

   Returns an empty vector when the ref does not exist or has no commits."
  [parent-repo-path workflow-id]
  (let [ref-name (scratch-ref-name workflow-id)
        fmt      (str "%H" sep "%s" sep "%at")
        r        (sh-git parent-repo-path "log" (str "--format=" fmt) ref-name)]
    (if-not (git-ok? r)
      []
      (->> (str/split-lines (out-trim r))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [parts   (str/split line sep-pattern 3)
                         sha     (str/trim (get parts 0 ""))
                         subject (str/trim (get parts 1 ""))
                         ts-str  (str/trim (get parts 2 "0"))]
                     {:commit-sha      sha
                      :message         subject
                      :timestamp-epoch (try (Long/parseLong ts-str)
                                            (catch Exception _ 0))
                      :workflow-id     workflow-id})))))))

(defn gc-scratch-refs!
  "Delete scratch refs whose tip commit is older than `max-age-days` days.

   Uses `git for-each-ref` to enumerate all refs under
   refs/miniforge/scratch/ and inspects the creatordate (committer-date for
   commits, tagger-date for tags).  Deletes qualifying refs via
   `git update-ref -d`.

   A ref qualifies for deletion when:
     (current-epoch-seconds - creator-epoch-seconds) >= (max-age-days * 86400)

   Using >= means max-age-days=0 deletes all existing scratch refs immediately,
   which is useful for testing and emergency cleanup.

   Returns:
     result/ok  {:deleted [ref-name ...] :retained int}
     result/err {:code :scratch-commit/gc-list-failed} when for-each-ref fails"
  [parent-repo-path max-age-days]
  (try
    (let [fmt (str "%(refname)" sep "%(creatordate:unix)")
          r   (sh-git parent-repo-path
                      "for-each-ref"
                      (str "--format=" fmt)
                      "refs/miniforge/scratch/")]
      (if-not (git-ok? r)
        (result/err :scratch-commit/gc-list-failed
                    (str "for-each-ref failed: " (:err r))
                    {:stderr (:err r)})

        (let [now-s     (quot (System/currentTimeMillis) 1000)
              max-age-s (* max-age-days 86400)
              lines     (->> (str/split-lines (out-trim r)) (remove str/blank?))
              parsed    (mapv (fn [line]
                                (let [parts (str/split line sep-pattern 2)
                                      ref   (str/trim (get parts 0 ""))
                                      ts    (try (Long/parseLong
                                                  (str/trim (get parts 1 "0")))
                                                 (catch Exception _ 0))]
                                  {:ref ref :ts ts}))
                              lines)
              to-delete (filter #(>= (- now-s (:ts %)) max-age-s) parsed)
              deleted   (reduce (fn [acc {:keys [ref]}]
                                  (let [del-r (sh-git parent-repo-path
                                                      "update-ref" "-d" ref)]
                                    (if (git-ok? del-r) (conj acc ref) acc)))
                                []
                                to-delete)]
          (result/ok {:deleted  deleted
                      :retained (- (count parsed) (count deleted))}))))

    (catch Exception e
      (result/err :scratch-commit/gc-unexpected
                  (str "unexpected error in gc-scratch-refs!: " (.getMessage e))))))

;;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; scratch-ref-name is pure — no I/O required.
  (scratch-ref-name "wf-2026-05-24-abc")
  ;=> "refs/miniforge/scratch/wf-2026-05-24-abc"

  ;; Snapshot a single file into the object store of the parent repo.
  (scratch-commit! "/path/to/parent-repo"
                   "wf-2026-05-24-abc"
                   "implement"
                   "/path/to/parent-repo/components/foo/artifact.edn")
  ;=> {:status :ok
  ;    :data {:commit-sha "deadbeef..." :ref "refs/miniforge/scratch/wf-..." ...}}

  ;; List all snapshots for a workflow (newest first).
  (list-scratch-commits "/path/to/parent-repo" "wf-2026-05-24-abc")
  ;=> [{:commit-sha "..." :message "scratch: wf-... implement ..."
  ;     :timestamp-epoch 1716540000 :workflow-id "wf-2026-05-24-abc"}]

  ;; GC refs older than 7 days.
  (gc-scratch-refs! "/path/to/parent-repo" 7)
  ;=> {:status :ok :data {:deleted [] :retained 1}}

  :leave-this-here)
