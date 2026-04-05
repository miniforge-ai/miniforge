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

(ns ai.miniforge.compliance-scanner.factory
  "Factory functions for compliance-scanner domain maps.

   Single source of truth for constructing Violation, ScanResult,
   PlanTask, Plan, PlanSummary, and DeltaReport maps.

   Layer 0 — pure data construction, no I/O or side effects.")

;------------------------------------------------------------------------------ Layer 0
;; Violation

(defn ->violation
  "Create a Violation map."
  [rule-id rule-category title file line current suggested auto-fixable? rationale]
  {:rule/id       rule-id
   :rule/category rule-category
   :rule/title    title
   :file          file
   :line          line
   :current       current
   :suggested     suggested
   :auto-fixable? auto-fixable?
   :rationale     rationale})

;------------------------------------------------------------------------------ Layer 0
;; ScanResult

(defn ->scan-result
  "Create a ScanResult map."
  [violations rules-scanned files-scanned scan-duration-ms]
  {:violations       violations
   :rules-scanned    rules-scanned
   :files-scanned    files-scanned
   :scan-duration-ms scan-duration-ms})

;------------------------------------------------------------------------------ Layer 0
;; PlanSummary

(defn ->plan-summary
  "Create a PlanSummary map from a collection of violations."
  [violations]
  (let [auto-fixable (filter :auto-fixable? violations)
        needs-review (remove :auto-fixable? violations)
        files        (->> violations (map :file) distinct count)
        rules        (->> violations (map :rule/id) distinct count)]
    {:total-violations (count violations)
     :auto-fixable     (count auto-fixable)
     :needs-review     (count needs-review)
     :files-affected   files
     :rules-violated   rules}))

;------------------------------------------------------------------------------ Layer 0
;; PlanTask

(defn ->plan-task
  "Create a PlanTask map."
  [id deps file rule-id violations]
  {:task/id         id
   :task/deps       deps
   :task/file       file
   :task/rule-id    rule-id
   :task/violations violations})

;------------------------------------------------------------------------------ Layer 0
;; Plan

(defn ->plan
  "Create a Plan map."
  [dag-tasks work-spec summary]
  {:dag-tasks dag-tasks
   :work-spec work-spec
   :summary   summary})

;------------------------------------------------------------------------------ Layer 0
;; DeltaReport

(defn ->delta-report
  "Create a DeltaReport map."
  [repo-path standards-path scan-timestamp summary violations]
  {:repo-path      repo-path
   :standards-path standards-path
   :scan-timestamp scan-timestamp
   :summary        summary
   :violations     violations})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (->violation :std/clojure "210" "Clojure Map Access"
               "components/foo/src/ai/miniforge/foo/core.clj"
               42
               "(or (:k m) default)"
               "(get m :k default)"
               true
               "Literal default, non-JSON field")

  (->plan-summary
   [{:auto-fixable? true  :file "a.clj" :rule/id :std/clojure}
    {:auto-fixable? false :file "a.clj" :rule/id :std/header-copyright}
    {:auto-fixable? true  :file "b.clj" :rule/id :std/clojure}])
  ;; => {:total-violations 3, :auto-fixable 2, :needs-review 1,
  ;;     :files-affected 2, :rules-violated 2}

  :leave-this-here)
