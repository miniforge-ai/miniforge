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
(ns ai.miniforge.phase-software-factory.release-scan
  "Release's scan of the work a task branch committed beyond its base
   commits, and the failure shape every release scan reports.

   A failed scan is never an empty one: release skips on an empty scan, so
   a git error read as \"no changes\" would complete the release as skipped
   and leave committed work unpublished. Every failure comes back as a
   `:release/git-status-failed` anomaly, which release raises and retries.

   Paths come from `git diff -z`, which never quotes them: without -z git
   wraps a path with non-ASCII bytes in C-style quotes, which names no file
   on disk.

   Only regular files are read. Release ships the committed work as the
   commits themselves; file content feeds the PR metadata and the
   releaser's prompt, so a symlink's target (possibly outside the
   worktree) or a submodule directory must never be read into it. Those
   entries, like deletions, carry their path and action with empty
   content."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.phase-software-factory.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} command-failure
  "Failure detail for one scan command, or nil when it exited 0. A capsule
   executor that could not run the command returns `{:ok? false :error ...}`
   with no exit code; a missing exit code counts as failure, as in verify."
  [command exit stderr executor-error]
  (when-not (= 0 exit)
    {:detail (or (not-empty (str/trim (str stderr)))
                 (:message executor-error)
                 (str command " exited " exit))
     :data   (cond-> {:command command :exit exit :stderr stderr}
               executor-error (assoc :executor/error executor-error))}))

(defn ^{:stratum 0} git-status-failed
  "Anomaly for a working-tree scan that could not run (git exited non-zero,
   the capsule executor failed, or a listed file could not be read).
   Distinct from the terminal `:release/zero-files`: a failed scan says
   nothing about whether there is work to release. `error-release` retries
   it in place like any non-terminal release error (what a held index.lock
   needs). `failure` is `{:detail string :data map}`."
  [root-path {:keys [detail data]}]
  (anomaly/sub-anomaly :unavailable
                       :anomalies.phase/enter-failed
                       (messages/t :release/git-status-failed
                                   {:worktree-path root-path :detail detail})
                       (merge {:type          :release/git-status-failed
                               :phase         :release
                               :worktree-path root-path}
                              data)))

(def ^{:stratum 0} regular-file-modes
  "Git modes of a regular file (plain and executable)."
  #{"100644" "100755"})

(defn ^{:stratum 0} raw-z-entries
  "Parse `git diff --raw --no-renames -z` output into
   {:status :mode :path} entries, `:mode` being the worktree side's. With
   -z each record is `:<old-mode> <new-mode> <old-sha> <new-sha> <status>`
   and its path as separate NUL-terminated fields, the path never quoted."
  [out]
  (->> (str/split (or out "") #"\u0000")
       (remove empty?)
       (partition 2)
       (mapv (fn [[record path]]
               (let [[_old-mode new-mode _old-sha _new-sha status]
                     (str/split (subs record 1) #" ")]
                 {:status status :mode new-mode :path path})))))

(defn ^{:stratum 0} entry-action
  [status]
  (case (first status)
    \A :create
    \D :delete
    :modify))

(defn ^{:stratum 0} host-reader
  "Read `path` under `root-path` on the host: {:content s} or {:failure f}.
   Refuses a path whose canonical target leaves `root-path`."
  [root-path]
  (fn [path]
    (try
      (let [root   (.getCanonicalFile (io/file root-path))
            target (.getCanonicalFile (io/file root-path path))]
        (if (.startsWith (.toPath target) (.toPath root))
          {:content (slurp target)}
          {:failure {:detail (str path " resolves outside the worktree")
                     :data   {:path path}}}))
      (catch Exception e
        {:failure {:detail (or (ex-message e) (.getName (class e)))
                   :data   {:path path :exception/class (.getName (class e))}}}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} host-runner
  "Run git with `args` in `root-path` on the host: {:exit :out :failure}."
  [root-path]
  (fn [args]
    (let [command (str/join " " (cons "git" args))]
      (try
        (let [{:keys [exit out err]} (apply shell/sh "git" (concat args [:dir root-path]))]
          {:exit exit :out out :failure (command-failure command exit err nil)})
        (catch Exception e
          {:failure {:detail (or (ex-message e) (.getName (class e)))
                     :data   {:command command :exception/class (.getName (class e))}}})))))

(defn ^{:stratum 1} capsule-runner
  "Run git with `args` in `root-path` inside a capsule through `execute-fn`
   (dag-executor's execute! signature): {:exit :out :failure}. Each argument
   is shell-quoted, as the command runs under `sh -c`."
  [execute-fn executor env-id root-path]
  (fn [args]
    (let [command (str/join " " (map agent/shell-quote (cons "git" args)))]
      (try
        (let [result (execute-fn executor env-id command {:workdir root-path})
              exit   (get-in result [:data :exit-code])]
          {:exit    exit
           :out     (or (get-in result [:data :stdout]) "")
           :failure (command-failure command exit (get-in result [:data :stderr]) (:error result))})
        (catch Exception e
          {:failure {:detail (or (ex-message e) (.getName (class e)))
                     :data   {:command command :exception/class (.getName (class e))}}})))))

(defn ^{:stratum 1} capsule-reader
  "Read `path` under `root-path` inside a capsule: {:content s} or {:failure f}."
  [execute-fn executor env-id root-path]
  (fn [path]
    (let [command (str "cat " (agent/shell-quote (str root-path "/" path)))]
      (try
        (let [result (execute-fn executor env-id command {:workdir root-path})
              exit   (get-in result [:data :exit-code])]
          (if-let [failure (command-failure command exit (get-in result [:data :stderr]) (:error result))]
            {:failure failure}
            {:content (or (get-in result [:data :stdout]) "")}))
        (catch Exception e
          {:failure {:detail (or (ex-message e) (.getName (class e)))
                     :data   {:command command :exception/class (.getName (class e))}}})))))

(defn ^{:stratum 1} base-diff
  "The substantive {:status :mode :path} entries the worktree differs by
   from commit `sha`; nil when `sha` names no commit in this repository (an
   older base a fresh clone never fetched); a `:release/git-status-failed`
   anomaly when git fails."
  [run root-path sha]
  (let [{:keys [exit failure]} (run ["rev-parse" "--verify" "--quiet" (str sha "^{commit}")])]
    (cond
      (= 1 exit) nil
      failure    (git-status-failed root-path failure)
      :else
      (let [{:keys [out failure]} (run ["diff" "--raw" "--no-renames" "-z" sha "--"])]
        (if failure
          (git-status-failed root-path failure)
          (filterv agent/substantive-file? (raw-z-entries out)))))))

(defn ^{:stratum 1} read-entries
  "`:code/files` for `entries`: a regular file's content read with
   `read-file`; a deletion, symlink or submodule with empty content (see
   the namespace doc). A `:release/git-status-failed` anomaly for the first
   regular file that cannot be read."
  [read-file root-path entries]
  (reduce (fn [files {:keys [status mode path]}]
            (let [action (entry-action status)]
              (if (or (= :delete action) (not (contains? regular-file-modes mode)))
                (conj files {:path path :content "" :action action})
                (let [{:keys [content failure]} (read-file path)]
                  (if failure
                    (reduced (git-status-failed root-path failure))
                    (conj files {:path path :content content :action action}))))))
          []
          entries))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} committed-files
  "`:code/files` for what the worktree holds beyond the first of
   `base-shas` that is a commit here, [] when it holds nothing beyond it
   (or none is); a `:release/git-status-failed` anomaly when a diff or a
   read fails. The first commit found decides, an empty diff included: a
   later base is only a fallback for an earlier one the repository lacks,
   so work a repair reverted is not counted against a newer base."
  [run read-file root-path base-shas]
  (reduce (fn [_ sha]
            (let [entries (base-diff run root-path sha)]
              (cond
                (nil? entries)             []
                (anomaly/anomaly? entries) (reduced entries)
                :else                      (reduced (read-entries read-file root-path entries)))))
          []
          base-shas))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} committed-since
  "The work the task branch at `root-path` holds beyond `base-shas`, read
   in the capsule for a governed run with an executor, else on the host.
   See `committed-files` for the result."
  [ctx root-path base-shas]
  (let [execute-fn (get ctx :execution/execute-fn)
        executor   (get ctx :execution/executor)
        env-id     (get ctx :execution/environment-id)]
    (if (and (= :governed (get ctx :execution/mode)) execute-fn executor env-id)
      (committed-files (capsule-runner execute-fn executor env-id root-path)
                       (capsule-reader execute-fn executor env-id root-path)
                       root-path base-shas)
      (committed-files (host-runner root-path) (host-reader root-path) root-path base-shas))))

(comment
  (committed-since {} "." ["HEAD~1"]))
