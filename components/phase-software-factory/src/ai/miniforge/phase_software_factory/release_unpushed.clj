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
(ns ai.miniforge.phase-software-factory.release-unpushed
  "Committed work a release has not published: the files that commits on
   HEAD, and on no ref of origin, change. The release skip probe reads it,
   so a clean worktree whose work sits in unpushed commits is released
   instead of skipped as done.

   Published means on origin, the remote the release pushes to: a chained
   DAG task's parent work is on its pushed branch there, and a
   single-branch clone has the ref it was cloned from. The run's own
   branches do not count: its release branch (a release that pushed but
   opened no PR is not done) and its checkpoint branches (workspace
   persistence pushes them, and a resume starts from one). Backend-neutral: the caller supplies how to run git (host
   or capsule).

   Split out of `release` (rule 210); the entries it builds are in
   `release-unpushed-entries`."
  (:require [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.phase-software-factory.release-unpushed-entries :as entries]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} head-command
  "Whether HEAD names a commit; with `--quiet` it exits 1 when it does not."
  ["git" "rev-parse" "--verify" "--quiet" "HEAD"])

(def ^{:stratum 0} origin-command
  "Whether the worktree has an origin to publish to."
  ["git" "remote" "get-url" "origin"])

(defn ^{:stratum 0} log-command
  "The paths that commits on HEAD touch, when they are on none of
   `base-commits` (where the task's work started) and on no origin ref
   save the run's own `excluded-branches`; merge commits' changes
   included, a rename as its two paths, NUL-separated. A base commit the
   repository lacks is ignored."
  [excluded-branches base-commits]
  (-> ["git" "log" "-m" "--no-renames" "--ignore-missing" "--format=" "--name-only" "-z"
       "HEAD" "--not"]
      (into (remove nil?) base-commits)
      (into (comp (remove nil?) (distinct) (map #(str "--exclude=origin/" %))) excluded-branches)
      (conj "--remotes=origin")))

(defn- ^{:stratum 0} answered? [result]
  (= 0 (:exit result)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unpushed-files
  "The files unpublished commits change, as :code/files entries with their
   content at HEAD, in {:files [...]}: a path HEAD lacks is a deletion, a
   submodule's is its commit. No commit yet is nothing to release. A
   missing origin, or git failing to answer or read a file, is
   {:failure {:detail :data}}: an unanswered scan is not an empty one.

   `run` runs a git argv in the worktree and returns {:exit :out :err};
   `run-branches` are the run's own branches (release and checkpoint);
   `base-commits` are where the task's work started, so commits that were
   there before it (a local base branch ahead of origin) are not its."
  [run run-branches base-commits]
  (let [head     (run head-command)
        origin   (when (answered? head) (run origin-command))
        log-cmd  (log-command run-branches base-commits)
        log      (when (answered? origin) (run log-cmd))
        paths    (when (answered? log)
                   (filterv #(agent/substantive-file? {:path %}) (distinct (entries/nul-fields (:out log)))))
        tree-cmd (into ["git" "ls-tree" "-z" "HEAD" "--"] paths)
        tree     (when (seq paths) (run tree-cmd))
        found    (when (answered? tree) (entries/tree-entries (:out tree)))
        files    (when found (mapv #(entries/file-entry run found %) paths))]
    (cond
      ;; Exit 1 with --quiet: HEAD names no commit yet. Anything else
      ;; but 0 (a timeout, a capsule error) is a scan that did not answer.
      (= 1 (:exit head))       {:files []}
      (not (answered? head))   (entries/failure head-command head)
      (not (answered? origin)) (entries/failure origin-command origin)
      (not (answered? log))    (entries/failure log-cmd log)
      (empty? paths)           {:files []}
      (not (answered? tree))   (entries/failure tree-cmd tree)
      :else                    (or (some #(when (:failure %) %) files) {:files files}))))

(comment ::release-unpushed)
