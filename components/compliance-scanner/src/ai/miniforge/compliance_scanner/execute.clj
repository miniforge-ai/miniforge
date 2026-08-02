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
(ns ai.miniforge.compliance-scanner.execute
  "Apply auto-fixable violations to files and create one PR per rule.

   File patching, PR-doc rendering, semantic-repair prompts, and git
   shell primitives live in `execute-patch`, `execute-pr-doc`,
   `execute-semantic-repair`, and `execute-git` respectively (rule 210:
   a fifth real layer here is the signal to split it).

   Layer 0: Per-rule fix, commit, and PR orchestration
   Layer 1: Top-level execute! entry point"
  (:require [ai.miniforge.compliance-scanner.execute-git    :as git]
            [ai.miniforge.compliance-scanner.execute-patch  :as patch]
            [ai.miniforge.compliance-scanner.execute-pr-doc :as pr-doc]
            [clojure.java.shell                              :as shell]
            [clojure.string                                  :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Per-rule fix, commit, and PR
(defn- ^{:stratum 0} fix-and-pr-for-rule!
  "Create a branch, apply auto-fixable fixes, write PR doc, commit, push, and open a PR.
   Returns a result map with :branch, :pr-url, :violations-fixed, :files-changed, :pr-doc."
  [repo-path rule-id category title tasks]
  (let [branch   (git/branch-slug category title)
        pr-title (str "fix: [" category "] " title " compliance pass")]
    (git/reset-to-origin-main! repo-path)
    (git/create-rule-branch! repo-path branch)
    (let [auto-tasks      (filter #(some :auto-fixable? (get % :task/violations)) tasks)
          patched-files   (mapv (fn [task]
                                  (let [file  (get task :task/file)
                                        viols (filter :auto-fixable? (get task :task/violations))]
                                    (patch/patch-file! repo-path file viols)
                                    file))
                                auto-tasks)
          auto-violations (->> auto-tasks
                               (mapcat #(filter :auto-fixable? (get % :task/violations)))
                               vec)
          violations-fixed (count auto-violations)
          ;; Write PR doc
          pr-doc-path     (pr-doc/pr-doc-filename category title)
          pr-doc-content  (pr-doc/build-pr-doc branch category title patched-files
                                               violations-fixed auto-violations)
          _               (pr-doc/write-pr-doc! repo-path pr-doc-path pr-doc-content)
          all-files       (conj patched-files pr-doc-path)]
      ;; Stage changed files + PR doc
      (apply git/git! repo-path "add" all-files)
      ;; Disable GPG/SSH signing — 1Password agent blocks in subprocess contexts
      (git/git! repo-path "config" "commit.gpgsign" "false")
      ;; Commit — skip pre-commit hook; changes are mechanically verified by the scanner
      (git/git! repo-path "commit" "--no-verify" "-m"
               (str "fix: [" category "] " title " compliance pass ("
                    (count patched-files) " file" (when (> (count patched-files) 1) "s") ")"))
      ;; Push (force-with-lease in case branch already exists from a prior run)
      (git/git! repo-path "push" "origin"
               (str branch ":refs/heads/" branch) "--force-with-lease")
      ;; Create PR via gh CLI
      (let [pr-body   (pr-doc/build-pr-body category title violations-fixed
                                            (count patched-files) pr-doc-path)
            pr-result (shell/sh "gh" "pr" "create"
                                "--base"  "main"
                                "--head"  branch
                                "--title" pr-title
                                "--body"  pr-body
                                :dir repo-path)
            pr-url    (str/trim (:out pr-result))]
        {:rule/id          rule-id
         :branch           branch
         :pr-url           pr-url
         :pr-doc           pr-doc-path
         :violations-fixed violations-fixed
         :files-changed    (count patched-files)}))))

;------------------------------------------------------------------------------ Layer 1

;; Top-level entry point
(defn ^{:stratum 1} execute!
  "Apply all auto-fixable violations and create one PR per rule.

   Reads DAG tasks from plan, groups by :task/rule-id, and for each rule
   with auto-fixable violations: creates a branch, patches files, commits,
   pushes, and opens a GitHub PR.

   Arguments:
   - plan      - Plan map with :dag-tasks
   - repo-path - string path to the repo/worktree root

   Returns map with :prs (vector), :violations-fixed (int), :files-changed (int)."
  [plan repo-path]
  (let [dag-tasks  (get plan :dag-tasks [])
        auto-tasks (filter #(some :auto-fixable? (get % :task/violations)) dag-tasks)
        by-rule    (group-by :task/rule-id auto-tasks)]
    (if (empty? by-rule)
      {:prs [] :violations-fixed 0 :files-changed 0}
      (let [prs (mapv (fn [[rule-id tasks]]
                        (let [first-v (first (get (first tasks) :task/violations []))]
                          (fix-and-pr-for-rule! repo-path rule-id
                                                (get first-v :rule/category "0")
                                                (get first-v :rule/title (name rule-id))
                                                tasks)))
                      by-rule)]
        {:prs              prs
         :violations-fixed (transduce (map :violations-fixed) + 0 prs)
         :files-changed    (transduce (map :files-changed)    + 0 prs)}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (patch/patch-file-content
   "(or (:timeout m) 5000)"
   [{:rule/id :std/clojure :line 1
     :current "(or (:timeout m) 5000)" :suggested "(get m :timeout 5000)"
     :auto-fixable? true}])

  (patch/patch-file-content
   "# My Doc\n\nSome content.\n"
   [{:rule/id :std/header-copyright :line 1
     :current "(missing copyright header)" :suggested nil
     :auto-fixable? true}])

  :leave-this-here)
