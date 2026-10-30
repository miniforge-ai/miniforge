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
  "Committed work a release has not published: the files the branch's
   commits change since it left origin's default branch. The release skip
   probe reads it, so a clean worktree whose work sits in unpushed commits
   is released instead of skipped as done. Backend-neutral: the caller
   supplies how to run git and read a file (host or capsule).

   Split out of `release` (rule 210)."
  (:require [ai.miniforge.agent.interface :as agent]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} name-status-entries
  "Parse `git diff --name-status -z` output into [status path] pairs. A
   rename or copy carries its source and destination; the destination is
   kept."
  [out]
  (loop [tokens (remove str/blank? (str/split (or out "") #"\u0000"))
         entries []]
    (if-let [status (first tokens)]
      (if (#{\R \C} (first status))
        (recur (drop 3 tokens) (conj entries [(str (first status)) (nth tokens 2 nil)]))
        (recur (drop 2 tokens) (conj entries [status (second tokens)])))
      (filterv second entries))))

(defn- ^{:stratum 0} failure
  [command {:keys [exit err]}]
  {:failure {:detail (or (not-empty (str/trim (str err))) (str command " exited " exit))
             :data   {:command command :exit exit :stderr err}}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unpushed-files
  "The files the branch's commits change since `origin/<default>` (three-dot,
   so a moved origin does not count), as :code/files entries, in
   {:files [...]}. {:failure {:detail :data}} when git cannot answer (no
   origin, say): an unanswered scan is not an empty one.

   `run` runs a git argv in the worktree and returns {:exit :out :err};
   `read-file` returns a path's content, or nil when it cannot."
  [run read-file]
  (let [head-ref (run ["git" "symbolic-ref" "refs/remotes/origin/HEAD"])
        base     (if (= 0 (:exit head-ref))
                   (str/replace (str/trim (:out head-ref)) "refs/remotes/origin/" "")
                   "main")
        command  ["git" "diff" "--name-status" "-z" (str "origin/" base "...HEAD")]
        diff     (run command)]
    (if-not (= 0 (:exit diff))
      (failure (str/join " " command) diff)
      (let [entries (for [[status path] (name-status-entries (:out diff))
                          :when (agent/substantive-file? {:path path})]
                      (if (= "D" status)
                        {:path path :content "" :action :delete}
                        (let [content (read-file path)]
                          (if (nil? content)
                            {:unreadable path}
                            {:path path :content content
                             :action (if (= "A" status) :create :modify)}))))]
        (if-let [unreadable (some :unreadable entries)]
          {:failure {:detail (str "could not read " unreadable) :data {:path unreadable}}}
          {:files (vec entries)})))))
