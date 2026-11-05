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
(ns ai.miniforge.phase-software-factory.release-worktree
  "What the release finds to publish in the task's worktree: the dirty
   files `git status` lists (on the host or in the capsule's checkout),
   the files the task branch committed beyond its base commits, and the
   `:release/zero-files` failure when there are none.

   Split out of `release` (rule 210); the definitions moved unchanged."
  (:require [clojure.java.io :as io]
            [ai.miniforge.anomaly.interface :as anomaly]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.phase.interface.task-base :as task-base]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.phase-software-factory.release-scan :as release-scan]
            [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private commit-sha-pattern
  "A full or abbreviated commit sha: 7 to 40 hex digits (SHA-1), up to 64
   (SHA-256 repositories). A branch name, a range or any other ref fails it."
  #"[0-9a-f]{7,64}")

(def ^{:stratum 0} ^:private resume-range-pattern
  "A range `X...HEAD`, which implement records for a resumed workspace that
   had no recorded task base (`implement/base-ref-candidates`): the task's
   work is what HEAD holds beyond its merge base with X. Group 1 is X."
  #"(.+)\.\.\.HEAD")

(defn- ^{:stratum 0} merge-base-sha
  "The commit a `ref...HEAD` range measures from: `git merge-base ref HEAD`,
   run with `run` (see release-scan). nil when `ref` names no commit here
   or shares no history with HEAD, so the next base is tried; a
   `:release/git-status-failed` anomaly when git fails."
  [run root-path ref]
  (let [verified (run ["rev-parse" "--verify" "--quiet" (str ref "^{commit}")])]
    (cond
      (release-scan/missing-commit? verified) nil
      (:failure verified) (release-scan/git-status-failed root-path (:failure verified))
      :else
      (let [{:keys [out failure] :as based} (run ["merge-base" ref "HEAD"])]
        (cond
          ;; exit 1 printing nothing, on stdout or stderr: the two share no
          ;; history. An exit 1 with stderr (a capsule whose container is
          ;; gone) is a failure, not unrelated history.
          (release-scan/missing-commit? based) nil
          failure (release-scan/git-status-failed root-path failure)
          :else (str/trim out))))))

(defn- ^{:stratum 0} porcelain-scan-entry
  "A release-scan entry for one `git status` [status path] pair: no mode,
   so release-scan reads it as a file unless it was deleted."
  [[xy path]]
  (let [status (cond (str/includes? xy "D") "D"
                     (= "??" xy)            "A"
                     :else                  "M")]
    {:status status :path path}))

(defn- ^{:stratum 0} scan-io
  "The git runner, file reader and root a scan uses: in the capsule's
   checkout for a governed run (`capsule`, from capsule-target), else on
   the host in `worktree-path`."
  [worktree-path capsule]
  (if-let [{:keys [execute-fn executor env-id workdir]} capsule]
    {:run  (release-scan/capsule-runner execute-fn executor env-id workdir)
     :read (release-scan/capsule-reader execute-fn executor env-id workdir)
     :root workdir}
    {:run  (release-scan/host-runner worktree-path)
     :read (release-scan/host-reader worktree-path)
     :root worktree-path}))

(defn- ^{:stratum 0} porcelain-z-entries
  "Parse `git status --porcelain -z` output into [status path] pairs.
   With -z each entry is NUL-terminated and its path is never quoted or
   escaped (without -z git wraps a path with spaces or non-ASCII bytes in
   C-style quotes). A rename or copy entry is followed by its source
   path, which is skipped."
  [out]
  (loop [records (str/split (or out "") #"\u0000")
         entries []]
    (if-let [record (first records)]
      (if (< (count record) 4)
        (recur (next records) entries)
        (let [status (subs record 0 2)]
          (recur (if (some #{\R \C} status) (nnext records) (next records))
                 (conj entries [status (subs record 3)]))))
      entries)))

(defn- ^{:stratum 0} zero-files-data
  [impl-result worktree-path]
  {:type           :release/zero-files
   :phase          :release
   :environment-id (:environment-id impl-result)
   :worktree-path  worktree-path})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} release-bases
  "Where the task branch is measured from, in order: the task base refs
   implement recorded (checkpointed, so a resumed run restores the first
   attempt's), then, on a first attempt, the environment's base commit. A
   commit sha is used as is, `{:sha s}`; an `X...HEAD` range is its merge
   base with HEAD, `{:merge-base X}`.

   A resumed environment's base commit is the checkpoint it restored,
   which already holds the run's earlier work, so it is not a base: not
   as the environment's, and not as the one commit among the refs a
   resume recorded (they carry `X...HEAD` ranges; that commit is the
   resumed environment's). Measuring from it would find nothing and skip
   the release.

   Never a branch name or the commit the CLI started on by itself: `git
   diff <ref>` compares the working tree with the ref itself, so a ref
   behind the task's real base would count upstream changes as this run's
   work."
  [ctx]
  (let [recorded     (filter string? (task-base/recorded-refs ctx))
        resumed?     (some? (get-in ctx [:execution/opts :resume-workspace]))
        base-sha     (get-in ctx [:execution/environment-metadata :base-sha])
        from-resume? (boolean (some #(re-matches resume-range-pattern %) recorded))]
    (into []
          (comp (keep (fn [ref]
                        (if-let [[_ left] (re-matches resume-range-pattern ref)]
                          {:merge-base left}
                          (when (and (not from-resume?) (re-matches commit-sha-pattern ref))
                            {:sha ref}))))
                (distinct))
          (concat recorded (when (and (not resumed?) (string? base-sha)) [base-sha])))))

(defn- ^{:stratum 1} base-shas
  "The commits `bases` (see release-bases) name in this repository, in
   order: a sha as is, a merge base looked up with `run`; a base that
   names no commit here is left out. The first failed lookup's
   `:release/git-status-failed` anomaly instead when git fails."
  [run root-path bases]
  (reduce (fn [shas {:keys [sha merge-base]}]
            (let [resolved (if merge-base (merge-base-sha run root-path merge-base) sha)]
              (cond
                (anomaly/anomaly? resolved) (reduced resolved)
                (nil? resolved)             shas
                :else                       (conj shas resolved))))
          []
          bases))

(defn- ^{:stratum 1} git-dirty-files
  "Scan git working tree for new/modified/deleted files; return as :code/files entries.
   Used as fallback when the implement phase wrote files directly to disk
   (design docs, specs, UX assets, etc.) rather than returning them in a
   :code/files artifact.

   Filters out non-substantive paths (session markers, miniforge runtime
   artifacts) via `agent/substantive-file?` — a diff consisting only of
   these must not count as releasable work. Matches the same filter the
   curator applies upstream (iter-23 regression: empty-diff PRs).

   Reads `git status -z`, whose paths are never quoted: without -z git
   wraps a path with a space or non-ASCII bytes in C-style quotes, which
   names no file on disk, and the file was left out of the release.

   Runs git with `release-executor/local-git-env`: a git hook exports
   GIT_DIR and GIT_WORK_TREE, and a git that inherits them scans the
   hook's repository instead of `root-path`.

   Returns a `:release/git-status-failed` anomaly when the scan cannot
   run, never `[]` (which release would report as zero files)."
  [root-path]
  (try
    (let [{:keys [exit out err]} (shell/sh "git" "status" "--porcelain" "-uall" "-z" :dir root-path
                                             :env (release-executor/local-git-env))]
      (if-let [failure (release-scan/command-failure "git status --porcelain -uall -z" exit err nil)]
        (release-scan/git-status-failed root-path failure)
        (->> (porcelain-z-entries out)
             (keep (fn [[xy path]]
                     (let [file   (io/file root-path path)
                           action (if (str/starts-with? (str/trim xy) "?") :create :modify)]
                       (cond
                         (str/includes? xy "D") {:path path :content "" :action :delete}
                         ;; Keep a symlink's path but never follow it: its
                         ;; target may lie outside the worktree (see release-scan).
                         (java.nio.file.Files/isSymbolicLink (.toPath file))
                                                {:path path :content "" :action action}
                         (.isFile file)         {:path path :content (slurp file) :action action}))))
             (filter agent/substantive-file?)
             vec)))
    (catch Exception e
      (release-scan/git-status-failed root-path (release-scan/exception-failure e {})))))

(defn- ^{:stratum 1} git-dirty-files-capsule
  "Scan git working tree inside a task capsule via execute-fn (N11 §9.3).
   Used for K8s executors where the filesystem is not locally accessible.
   execute-fn is dag-exec/execute! passed through context to avoid cross-component requires.
   `workdir` is the capsule's checkout (see capsule-target).

   Applies the same `agent/substantive-file?` filter as the local variant so
   session markers and miniforge runtime artifacts don't slip through the
   governed path (iter-23 empty-diff regression).

   Returns a `:release/git-status-failed` anomaly when `git status` or a
   file's `cat` fails in the capsule, rather than `[]` or empty content."
  [execute-fn executor env-id workdir]
  (let [run                   (release-scan/capsule-runner execute-fn executor env-id workdir)
        read-file             (release-scan/capsule-reader execute-fn executor env-id workdir)
        {:keys [out failure]} (run ["status" "--porcelain" "-uall" "-z"])
        entries               (->> (porcelain-z-entries out)
                                   (filter (fn [[_status path]] (agent/substantive-file? {:path path})))
                                   (map porcelain-scan-entry))]
    (if failure
      (release-scan/git-status-failed workdir failure)
      (release-scan/read-entries read-file workdir entries))))

(defn- ^{:stratum 1} release-files-result
  "Return release file data, the failed scan's own anomaly when `dirty` is
   one, or a canonical anomaly when there is no substantive work to release."
  [committed dirty impl-result worktree-path]
  (cond
    (seq committed)          committed
    (anomaly/anomaly? dirty) dirty
    (seq dirty)              dirty
    :else
    (anomaly/sub-anomaly :invalid-input
                         :anomalies.phase/enter-failed
                         (messages/t :release/zero-files)
                         (zero-files-data impl-result worktree-path))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} committed-since-base
  "Substantive `:code/files` the task branch holds beyond its base commits
   (committed and dirty alike), limited to paths a commit other than a
   verify or review boundary commit touched (see release-scan); [] when
   there are none or no base commit is known, or a
   `:release/git-status-failed` anomaly when the scan fails. A
   clean worktree is not \"nothing to release\" when an earlier implement
   iteration's commits are still unreleased. Runs in the capsule for a
   governed run (`capsule`, from capsule-target), else on the host."
  [ctx worktree-path capsule]
  (let [{:keys [run read root]} (scan-io worktree-path capsule)
        shas                    (base-shas run root (release-bases ctx))
        entries                 (if (anomaly/anomaly? shas)
                                  shas
                                  (release-scan/first-base-diff run root shas))]
    (if (anomaly/anomaly? entries)
      entries
      (release-scan/read-entries read root entries))))

(defn ^{:stratum 2} dirty-scan
  "The worktree's dirty files: in the capsule's checkout for a governed run
   (`capsule`, from capsule-target), else on the host."
  [worktree-path capsule]
  (if-let [{:keys [execute-fn executor env-id workdir]} capsule]
    (git-dirty-files-capsule execute-fn executor env-id workdir)
    (git-dirty-files worktree-path)))

(defn ^{:stratum 2} release-files!
  "Boundary wrapper around the anomaly-returning `release-files-result`;
   retained for release phase control flow that treats `:release/zero-files`
   as a terminal exception."
  [committed dirty impl-result worktree-path]
  (let [result (release-files-result committed dirty impl-result worktree-path)]
    (if (anomaly/anomaly? result)
      (throw (ex-info (:anomaly/message result)
                      (:anomaly/data result)))
      result)))
