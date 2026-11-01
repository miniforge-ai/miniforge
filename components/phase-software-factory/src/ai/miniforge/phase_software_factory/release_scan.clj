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
  "Release's git scans, on the host or in a governed run's capsule: the
   work a task branch committed beyond its base commits, the readers both
   scans use, and the failure shape every release scan reports.

   A failed scan is never an empty one: release skips on an empty scan, so
   a git error read as \"no changes\" would complete the release as skipped
   and leave committed work unpublished. Every failure comes back as a
   `:release/git-status-failed` anomaly, which release raises and retries.

   Paths come from `git ... -z`, which never quotes them: without -z git
   wraps a path with non-ASCII bytes in C-style quotes, which names no file
   on disk.

   Only regular files are read. Release ships the committed work as the
   commits themselves; file content feeds the PR metadata and the
   releaser's prompt, so a symlink's target (possibly outside the
   worktree) or a submodule directory must never be read into it. A
   committed entry carries its git mode, and only modes 100644/100755 are
   read. An entry from `git status` has no mode: the readers themselves
   refuse a symlink (the host reader by canonical path, the capsule reader
   by `test -L`) and read anything that is not a regular file as empty.
   Deletions carry empty content.

   Layer 0: failure shapes, output parsing, constants
   Layer 1: argument quoting, host runner, readers, base-diff, read-entry
   Layer 2: capsule runner, first-base-diff, read-entries"
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
                 (messages/t :release/command-exited {:command command :exit exit}))
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

(defn ^{:stratum 0} exception-failure
  "Failure detail for exception `e`, with `data` describing what was run."
  [e data]
  {:detail (or (ex-message e) (.getName (class e)))
   :data   (assoc data :exception/class (.getName (class e)))})

(def ^{:stratum 0} ^:private regular-file-modes
  "Git modes of a regular file (plain and executable)."
  #{"100644" "100755"})

(def ^{:stratum 0} ^:private shell-safe-arg
  "Arguments made only of these characters reach `sh -c` unchanged, so they
   are passed unquoted."
  #"[A-Za-z0-9_./:=@%+-]+")

(defn- ^{:stratum 0} raw-z-entries
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

(defn- ^{:stratum 0} entry-action
  "The :code/files action for a git status letter (A added, D deleted;
   anything else is a modification)."
  [status]
  (case (first status)
    \A :create
    \D :delete
    :modify))

(defn- ^{:stratum 0} missing-commit?
  "True when a `rev-parse --verify --quiet` result says the ref names no
   commit here: it exits 1 and prints nothing. An exit 1 that printed
   something (a capsule whose container is gone also exits 1) is a
   failure."
  [{:keys [exit out err]}]
  (and (= 1 exit) (str/blank? out) (str/blank? err)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} shell-arg
  "`arg` for a `sh -c` command line, quoted unless it is shell-safe."
  [arg]
  (if (re-matches shell-safe-arg arg) arg (agent/shell-quote arg)))

(defn ^{:stratum 1} host-runner
  "Run git with `args` in `root-path` on the host: {:exit :out :err :failure}."
  [root-path]
  (fn [args]
    (let [command (str/join " " (cons "git" args))]
      (try
        (let [{:keys [exit out err]} (apply shell/sh "git" (concat args [:dir root-path]))]
          {:exit exit :out out :err err :failure (command-failure command exit err nil)})
        (catch Exception e
          {:failure (exception-failure e {:command command})})))))

(defn ^{:stratum 1} host-reader
  "Read `path` under `root-path` on the host: {:content s} or {:failure f}.
   Refuses a path whose canonical target leaves `root-path`, which covers a
   symlink pointing out of the worktree."
  [root-path]
  (fn [path]
    (try
      (let [root   (.getCanonicalFile (io/file root-path))
            target (.getCanonicalFile (io/file root-path path))]
        (if (.startsWith (.toPath target) (.toPath root))
          {:content (slurp target)}
          {:failure {:detail (messages/t :release/path-outside-worktree {:path path})
                     :data   {:path path}}}))
      (catch Exception e
        {:failure (exception-failure e {:path path})}))))

(defn ^{:stratum 1} capsule-reader
  "Read `path` under `workdir` inside a capsule: {:content s} or
   {:failure f}. A symlink, or anything that is not a regular file, reads
   as empty: its target is never followed."
  [execute-fn executor env-id workdir]
  (fn [path]
    (let [file    (agent/shell-quote (str workdir "/" path))
          command (str "if [ -f " file " ] && [ ! -L " file " ]; then cat " file "; fi")]
      (try
        (let [result  (execute-fn executor env-id command {:workdir workdir})
              exit    (get-in result [:data :exit-code])
              failure (command-failure command exit (get-in result [:data :stderr]) (:error result))]
          (if failure
            {:failure failure}
            {:content (get-in result [:data :stdout] "")}))
        (catch Exception e
          {:failure (exception-failure e {:command command})})))))

(defn- ^{:stratum 1} base-diff
  "The substantive {:status :mode :path} entries the worktree differs by
   from commit `sha`; nil when `sha` names no commit in this repository (an
   older base a fresh clone never fetched); a `:release/git-status-failed`
   anomaly when git fails."
  [run root-path sha]
  (let [verified (run ["rev-parse" "--verify" "--quiet" (str sha "^{commit}")])
        diffed   (delay (run ["diff" "--raw" "--no-renames" "-z" sha "--"]))]
    (cond
      (missing-commit? verified) nil
      (:failure verified)        (git-status-failed root-path (:failure verified))
      (:failure @diffed)         (git-status-failed root-path (:failure @diffed))
      :else                      (filterv agent/substantive-file? (raw-z-entries (:out @diffed))))))

(defn- ^{:stratum 1} read-entry
  "The :code/files entry for one {:status :mode :path} entry, or a
   `:release/git-status-failed` anomaly when its file cannot be read. A
   regular file, or an entry with no mode (from git status), is read with
   `read-file`; a deletion, symlink or submodule carries empty content."
  [read-file root-path {:keys [status mode path]}]
  (let [action  (entry-action status)
        read?   (and (not= :delete action)
                     (or (nil? mode) (contains? regular-file-modes mode)))
        {:keys [content failure]} (when read? (read-file path))]
    (if failure
      (git-status-failed root-path failure)
      {:path path :content (or content "") :action action})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} capsule-runner
  "Run git with `args` in `workdir` inside a capsule through `execute-fn`
   (dag-executor's execute! signature): {:exit :out :err :failure}. The
   command runs under `sh -c`, so an argument that is not shell-safe is
   quoted."
  [execute-fn executor env-id workdir]
  (fn [args]
    (let [command (str/join " " (map shell-arg (cons "git" args)))]
      (try
        (let [result (execute-fn executor env-id command {:workdir workdir})
              exit   (get-in result [:data :exit-code])
              err    (get-in result [:data :stderr])
              out    (get-in result [:data :stdout] "")]
          {:exit exit :out out :err err
           :failure (command-failure command exit err (:error result))})
        (catch Exception e
          {:failure (exception-failure e {:command command})})))))

(defn ^{:stratum 2} first-base-diff
  "The substantive entries the worktree differs by from the first of
   `base-shas` that is a commit here; [] when it differs by none (or no
   sha is a commit here); a `:release/git-status-failed` anomaly when git
   fails. The first commit found decides, an empty diff included: a later
   sha is only a fallback for an earlier one the repository lacks, so work
   a repair reverted is not counted against a newer base."
  [run root-path base-shas]
  (or (some #(base-diff run root-path %) base-shas) []))

(defn ^{:stratum 2} read-entries
  "`:code/files` for `entries` (see read-entry), or the first entry's
   `:release/git-status-failed` anomaly."
  [read-file root-path entries]
  (reduce (fn [files entry]
            (let [file (read-entry read-file root-path entry)]
              (if (anomaly/anomaly? file) (reduced file) (conj files file))))
          []
          entries))

(comment
  (read-entries (host-reader ".") "."
                (first-base-diff (host-runner ".") "." ["HEAD"])))
