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
(ns ai.miniforge.workflow.execution-dag-sync
  "Copy the changes each DAG sub-workflow made in its own worktree back
   into the parent worktree, so the release phase finds them.

   Split out of `execution` (rule 210); `execution-dag` calls
   `merge-sub-worktree-changes!` after a successful DAG run."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.messages :as messages]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} git-output-lines
  "Run `git args` in `dir`. Returns the non-blank output lines, or a :fault
   anomaly carrying the exit code and stderr when git exits non-zero."
  [dir args]
  (let [{:keys [exit out err]} (apply shell/sh "git" (concat args [:dir dir]))]
    (if (zero? exit)
      (vec (remove str/blank? (str/split-lines (or out ""))))
      (anomaly/anomaly :fault
                       (messages/t :dag.sync/git-failed {:args (str/join " " args)
                                                         :exit exit})
                       {:exit exit :err (str/trim (or err ""))}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} sync-sub-worktree!
  "Apply a DAG sub-worktree's changes to `parent-worktree`: tracked files
   that differ from HEAD, staged or not, and untracked files git does not
   ignore. A listed path the sub-worktree no longer has was deleted there,
   so it is deleted from the parent too. Returns nil on success, or a
   :fault anomaly naming both worktrees when git exits non-zero or an IO
   step throws."
  [parent-worktree sub-wt]
  (let [where {:sub-worktree sub-wt :parent-worktree parent-worktree}
        result (try+
                 (anomaly/let-ok
                   [changed   (git-output-lines sub-wt ["diff" "--name-only" "HEAD"])
                    untracked (git-output-lines sub-wt ["ls-files" "--others" "--exclude-standard"])]
                   (doseq [f (distinct (concat changed untracked))]
                     (let [src (io/file sub-wt f)
                           dst (io/file parent-worktree f)]
                       (cond
                         (.exists src) (do (io/make-parents dst)
                                           (io/copy src dst))
                         (.exists dst) (io/delete-file dst)))))
                 (catch Exception e
                   (anomaly/exception-anomaly :fault (messages/t :dag.sync/apply-failed) {} e)))]
    (when result
      (update result :anomaly/data merge where))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} merge-sub-worktree-changes!
  "Copy changed files from DAG sub-worktrees into the parent worktree.
   Each sub-workflow wrote to its own isolated worktree. For the release
   phase to find dirty files, we need to merge those changes back.

   Returns nil when every sub-worktree synced, or the anomaly for the first
   one that did not, after logging it. Stops there: the parent tree is
   already partial, so the caller must fail the run rather than release it."
  [parent-worktree sub-worktree-paths logger]
  (reduce
   (fn [_ sub-wt]
     (when-let [failure (sync-sub-worktree! parent-worktree sub-wt)]
       (log/error logger :workflow :workflow/sync-sub-worktree-failed
                  {:message (:anomaly/message failure)
                   :data    (:anomaly/data failure)})
       (reduced failure)))
   nil
   sub-worktree-paths))
