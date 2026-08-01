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
(ns ai.miniforge.compliance-scanner.execute-git
  "Git shell primitives for the per-rule fix/commit/PR flow, split out of
   `execute` (rule 210: a fifth real layer there is the signal to split
   it).

   Layer 0: Git command runner + branch-slug helper
   Layer 1: Branch setup (reset to origin/main, create rule branch)"
  (:require [clojure.java.shell :as shell]
            [clojure.string     :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Git shell helpers
(defn ^{:stratum 0} git!
  "Run a git command in repo-path directory. Returns the sh result map.
   Logs non-zero exit codes for debugging (branch -D failures are expected)."
  [repo-path & args]
  (let [result (apply shell/sh "git" (concat args [:dir repo-path]))]
    (when (and (not (zero? (:exit result)))
               (not (str/includes? (str args) "branch")))  ;; branch -D may fail safely
      (println (str "  git " (str/join " " args) " → exit " (:exit result)))
      (when-not (str/blank? (:err result))
        (println (str "  stderr: " (str/trim (:err result))))))
    result))

(defn ^{:stratum 0} branch-slug
  "Convert a rule category and title to a valid git branch segment."
  [category title]
  (-> (str/lower-case title)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"-+$" "")
      (->> (str "fix/compliance-" category "-"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} reset-to-origin-main!
  "Detach HEAD at origin/main, giving a clean base for the next rule branch."
  [repo-path]
  (git! repo-path "fetch" "origin" "main" "--quiet")
  (git! repo-path "checkout" "--detach" "origin/main"))

(defn ^{:stratum 1} create-rule-branch!
  "Create and checkout a new branch from origin/main. Deletes if already exists."
  [repo-path branch]
  (git! repo-path "branch" "-D" branch)  ;; safe to fail if absent
  (git! repo-path "checkout" "-b" branch "origin/main"))
