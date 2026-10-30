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
   release branch does not count: a release that pushed but opened no PR
   is not done. Backend-neutral: the caller supplies how to run git (host
   or capsule).

   Split out of `release` (rule 210)."
  (:require [ai.miniforge.agent.interface :as agent]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} log-command
  "The paths that commits on HEAD and on no origin ref (save
   `excluded-branch`, the run's release branch, when given) touch,
   merge commits' changes included, NUL-separated."
  [excluded-branch]
  (cond-> ["git" "log" "-m" "--format=" "--name-only" "-z" "HEAD" "--not"]
    excluded-branch (conj (str "--exclude=origin/" excluded-branch))
    true            (conj "--remotes=origin")))

(defn ^{:stratum 0} nul-fields
  "`out` split on NUL, keeping every non-empty field as it is: a path of
   spaces is a path."
  [out]
  (vec (remove empty? (str/split (or out "") #"\u0000"))))

(defn- ^{:stratum 0} failure
  [command {:keys [exit err]}]
  {:failure {:detail (or (not-empty (str/trim (str err)))
                         (str (str/join " " command) " exited " exit))
             :data   {:command (str/join " " command) :exit exit :stderr err}}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} tree-entries
  "`git ls-tree -z` output as {path {:type t :object sha}}."
  [out]
  (into {}
        (keep (fn [entry]
                (when-let [[_ type object path] (re-matches #"(?s)\d+ (\S+) (\S+)\t(.*)" entry)]
                  [path {:type type :object object}])))
        (nul-fields out)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unpushed-files
  "The files unpublished commits change, as :code/files entries with their
   content at HEAD, in {:files [...]}: a path HEAD lacks is a deletion, a
   submodule's is its commit. No commit yet is nothing to release. A
   missing origin, or git failing to answer or read a file, is
   {:failure {:detail :data}}: an unanswered scan is not an empty one.

   `run` runs a git argv in the worktree and returns {:exit :out :err};
   `run-branch` is the run's release branch, or nil."
  [run run-branch]
  (let [origin-cmd ["git" "remote" "get-url" "origin"]
        log-cmd    (log-command run-branch)]
    (if-not (= 0 (:exit (run ["git" "rev-parse" "--verify" "--quiet" "HEAD"])))
      {:files []}
      (let [origin (run origin-cmd)
            log    (when (= 0 (:exit origin)) (run log-cmd))
            paths  (filterv #(agent/substantive-file? {:path %}) (distinct (nul-fields (:out log))))
            tree-cmd (into ["git" "ls-tree" "-z" "HEAD" "--"] paths)
            tree   (when (and (= 0 (:exit log)) (seq paths)) (run tree-cmd))]
        (cond
          (not= 0 (:exit origin)) (failure origin-cmd origin)
          (not= 0 (:exit log))    (failure log-cmd log)
          (empty? paths)          {:files []}
          (not= 0 (:exit tree))   (failure tree-cmd tree)
          :else
          (let [entries (tree-entries (:out tree))
                files   (for [path paths
                              :let [{:keys [type object]} (get entries path)]]
                          (case type
                            nil      {:path path :content "" :action :delete}
                            "commit" {:path path :content (str "Subproject commit " object)
                                      :action :modify}
                            (let [cmd   ["git" "cat-file" "-p" object]
                                  shown (run cmd)]
                              (if (= 0 (:exit shown))
                                {:path path :content (:out shown) :action :modify}
                                (failure cmd shown)))))]
            (or (some #(when (:failure %) %) files)
                {:files (vec files)})))))))
