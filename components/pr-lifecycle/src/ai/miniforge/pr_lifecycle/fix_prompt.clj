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
(ns ai.miniforge.pr-lifecycle.fix-prompt
  "The prompt the fix agent gets, built from a fix context
   (fix-loop/create-fix-context)."
  (:require
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} review-change
  "{:file :line :change} for one review comment in any shape the fix loop
   is given: an extracted change, a triage result wrapping a parsed
   :comment, or the PR monitor's {:body :path :line}."
  [c]
  (let [comment (get c :comment c)]
    {:file (or (:file c) (:comment/path comment) (:path comment))
     :line (or (:line c) (:comment/line comment) (:line comment))
     :change (or (:change c) (:comment/body comment) (:body comment))}))

(defn ^{:stratum 0} review-line
  "One requested change as a prompt bullet: `- file:line` then the text."
  [{:keys [file line change]}]
  (str "- " file (when line (str ":" line)) "\n  " change))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-fix-prompt
  "Build a prompt for the fix agent based on failure type.

   Arguments:
   - fix-context: Context from create-fix-context

   Returns string prompt for the agent."
  [fix-context]
  (let [fix-type (:fix/type fix-context)
        task-id (:fix/task-id fix-context)
        summary (:fix/failure-summary fix-context)
        affected-files (str/join ", " (:fix/affected-files fix-context))]
    (case fix-type
      :ci-failure
      (str "Fix CI failures for task " task-id ".\n\n"
           "Summary: " summary "\n\n"
           "Affected files: " affected-files "\n\n"
           (when-let [tests (:fix/failing-tests fix-context)]
             (str "Failing tests:\n" (str/join "\n" (take 10 tests)) "\n\n"))
           (when-let [lint (:fix/lint-errors fix-context)]
             (str "Lint errors:\n" (str/join "\n" (take 10 lint)) "\n\n"))
           (when-let [build (:fix/build-errors fix-context)]
             (str "Build errors:\n" (str/join "\n" (take 5 build)) "\n\n"))
           (when-let [logs (:fix/ci-logs fix-context)]
             (str (messages/system-t :fix/ci-logs-heading) "\n" logs "\n\n"))
           "Generate fixes to resolve these issues while maintaining the original intent.")

      :review-changes
      (str "Address review feedback for task " task-id ".\n\n"
           "Requested changes:\n"
           (str/join "\n\n"
                     (->> (:fix/review-comments fix-context)
                          (map (comp review-line review-change))
                          (take 10)))
           "\n\nGenerate fixes that address these specific concerns.")

      :conflict
      (str "Resolve merge conflicts for task " task-id ".\n\n"
           "Conflicting files: " affected-files "\n\n"
           "Resolve the conflicts while preserving the intent of both changes.")

      ;; Default
      (str "Fix issues for task " task-id ".\n\n"
           "Summary: " summary "\n\n"
           "Affected files: " affected-files))))
