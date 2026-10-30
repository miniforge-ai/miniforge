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
   HEAD but on no remote-tracking ref change. The release skip probe reads
   it, so a clean worktree whose work sits in unpushed commits is released
   instead of skipped as done. Needs no base ref: a chained DAG task's
   parent work is on its pushed branch, and a single-branch clone has the
   ref it was cloned from. Backend-neutral: the caller supplies how to run
   git (host or capsule).

   Split out of `release` (rule 210)."
  (:require [ai.miniforge.agent.interface :as agent]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} log-command
  "The paths that commits on HEAD and on no remote-tracking ref touch."
  ["git" "log" "--format=" "--name-only" "-z" "HEAD" "--not" "--remotes"])

(defn ^{:stratum 0} log-paths
  "The distinct paths in `log-command` output, in order."
  [out]
  (->> (str/split (or out "") #"\u0000")
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unpushed-files
  "The files unpublished commits change, as :code/files entries with their
   content at HEAD (a path HEAD no longer has is a deletion), in
   {:files [...]}. {:failure {:detail :data}} when git cannot answer: an
   unanswered scan is not an empty one.

   `run` runs a git argv in the worktree and returns {:exit :out :err}."
  [run]
  (let [log (run log-command)]
    (if-not (= 0 (:exit log))
      {:failure {:detail (or (not-empty (str/trim (str (:err log))))
                             (str (str/join " " log-command) " exited " (:exit log)))
                 :data   {:command (str/join " " log-command) :exit (:exit log)
                          :stderr (:err log)}}}
      {:files (vec (for [path (log-paths (:out log))
                         :when (agent/substantive-file? {:path path})
                         :let [shown (run ["git" "show" (str "HEAD:" path)])]]
                     (if (= 0 (:exit shown))
                       {:path path :content (:out shown) :action :modify}
                       {:path path :content "" :action :delete})))})))
