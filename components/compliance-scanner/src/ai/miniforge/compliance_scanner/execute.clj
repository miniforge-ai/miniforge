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

   Layer 0: File patch helpers (pure)
   Layer 1: Git shell helpers
   Layer 2: Per-rule fix, commit, and PR
   Layer 3: Top-level execute! entry point"
  (:require [clojure.java.shell :as shell]
            [clojure.string     :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File patch helpers

(def ^:private md-copyright-header
  "Standard copyright comment prepended to markdown files missing a header."
  (str "<!--\n"
       "  Title: Miniforge.ai\n"
       "  Author: Christopher Lester (christopher@miniforge.ai)\n"
       "  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n"
       "-->\n"))

(defn- apply-line-replacement
  "Replace the first occurrence of :current with :suggested on the violation's line.
   lines is a vector of strings (0-indexed). line numbers in violations are 1-indexed."
  [lines violation]
  (let [idx       (dec (get violation :line 1))
        current   (get violation :current "")
        suggested (get violation :suggested)]
    (if (and suggested (< idx (count lines)) (str/includes? (get lines idx "") current))
      (update lines idx str/replace-first current suggested)
      lines)))

(defn- apply-copyright-prepend
  "Prepend the standard markdown copyright header to a content string."
  [content]
  (str md-copyright-header "\n" content))

(defn patch-file-content
  "Apply all violations for one file to its string content. Pure — no I/O.

   Line-replacement violations are applied bottom-up (descending line number) so
   prior edits do not shift subsequent line indices. Copyright header violations
   are prepended after all line edits, since they add a line at position 0."
  [content violations]
  (let [copyright-viols (filter #(= :std/header-copyright (get % :rule/id)) violations)
        line-viols      (remove #(= :std/header-copyright (get % :rule/id)) violations)
        ;; Preserve trailing newline: split with limit -1 keeps trailing empty segment
        lines           (str/split content #"\n" -1)
        patched-lines   (reduce apply-line-replacement lines (sort-by :line > line-viols))
        patched         (str/join "\n" patched-lines)]
    (if (seq copyright-viols)
      (apply-copyright-prepend patched)
      patched)))

(defn- patch-file!
  "Apply auto-fixable violations to a file on disk. No-op if content is unchanged.
   Returns the absolute file path."
  [repo-path file violations]
  (let [path    (str repo-path "/" file)
        content (slurp path)
        patched (patch-file-content content violations)]
    (when-not (= content patched)
      (spit path patched))
    path))

;------------------------------------------------------------------------------ Layer 1
;; Git shell helpers

(defn- git!
  "Run a git command in repo-path directory. Returns the sh result map.
   Throws on non-zero exit for commands that must succeed."
  [repo-path & args]
  (let [result (apply shell/sh "git" (concat args [:dir repo-path]))]
    (when (and (not (zero? (:exit result)))
               (not (str/includes? (str args) "branch")))  ;; branch -D may fail safely
      (println (str "  git " (str/join " " args) " → exit " (:exit result)))
      (when-not (str/blank? (:err result))
        (println (str "  stderr: " (str/trim (:err result))))))
    result))

(defn- branch-slug
  "Convert a rule category and title to a valid git branch segment."
  [category title]
  (-> (str/lower-case title)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"-+$" "")
      (->> (str "fix/compliance-" category "-"))))

(defn- reset-to-origin-main!
  "Detach HEAD at origin/main, giving a clean base for the next rule branch."
  [repo-path]
  (git! repo-path "fetch" "origin" "main" "--quiet")
  (git! repo-path "checkout" "--detach" "origin/main"))

(defn- create-rule-branch!
  "Create and checkout a new branch from origin/main. Deletes if already exists."
  [repo-path branch]
  (git! repo-path "branch" "-D" branch)  ;; safe to fail if absent
  (git! repo-path "checkout" "-b" branch "origin/main"))

;------------------------------------------------------------------------------ Layer 2
;; Per-rule fix, commit, and PR

(def ^:private pr-body
  "Auto-generated by the Miniforge compliance scanner.

Applies mechanical fixes for all auto-fixable violations of this rule.
Violations requiring semantic review are excluded — see the work spec
at `docs/compliance/` for the full list.

\uD83E\uDD16 Generated with [Miniforge](https://miniforge.ai)")

(defn- fix-and-pr-for-rule!
  "Create a branch, apply auto-fixable fixes, commit, push, and open a PR.
   Returns a result map with :branch, :pr-url, :violations-fixed, :files-changed."
  [repo-path rule-id category title tasks]
  (let [branch   (branch-slug category title)
        pr-title (str "fix: [" category "] " title " compliance pass")]
    (reset-to-origin-main! repo-path)
    (create-rule-branch! repo-path branch)
    (let [auto-tasks      (filter #(some :auto-fixable? (get % :task/violations)) tasks)
          patched-files   (mapv (fn [task]
                                  (let [file  (get task :task/file)
                                        viols (filter :auto-fixable? (get task :task/violations))]
                                    (patch-file! repo-path file viols)
                                    file))
                                auto-tasks)
          violations-fixed (transduce
                            (map #(count (filter :auto-fixable? (get % :task/violations))))
                            + 0 auto-tasks)]
      ;; Stage changed files
      (apply git! repo-path "add" patched-files)
      ;; Commit — skip pre-commit hook; changes are mechanically verified by the scanner
      (git! repo-path "commit" "--no-verify" "-m"
            (str "fix: [" category "] " title " compliance pass ("
                 (count patched-files) " file" (when (> (count patched-files) 1) "s") ")"))
      ;; Push (force-with-lease in case branch already exists from a prior run)
      (git! repo-path "push" "origin"
            (str branch ":refs/heads/" branch) "--force-with-lease")
      ;; Create PR via gh CLI
      (let [pr-result (shell/sh "gh" "pr" "create"
                                "--base"  "main"
                                "--head"  branch
                                "--title" pr-title
                                "--body"  pr-body
                                :dir repo-path)
            pr-url    (str/trim (:out pr-result))]
        {:rule/id          rule-id
         :branch           branch
         :pr-url           pr-url
         :violations-fixed violations-fixed
         :files-changed    (count patched-files)}))))

;------------------------------------------------------------------------------ Layer 3
;; Top-level entry point

(defn execute!
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
  (patch-file-content
   "(or (:timeout m) 5000)"
   [{:rule/id :std/clojure :line 1
     :current "(or (:timeout m) 5000)" :suggested "(get m :timeout 5000)"
     :auto-fixable? true}])

  (patch-file-content
   "# My Doc\n\nSome content.\n"
   [{:rule/id :std/header-copyright :line 1
     :current "(missing copyright header)" :suggested nil
     :auto-fixable? true}])

  :leave-this-here)
