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

(ns ai.miniforge.compliance-scanner.schema
  "Malli schemas for compliance-scanner domain types.

   Layer 0 — pure data definitions, no dependencies.")

;------------------------------------------------------------------------------ Layer 0
;; Violation — one detected instance of a rule breach

(def Violation
  "Schema for a single detected violation."
  [:map
   [:rule/dewey    [:string {:min 1}]]
   [:rule/title    :string]
   [:file          :string]
   [:line          :int]
   [:current       :string]
   [:suggested     [:maybe :string]]
   [:auto-fixable? :boolean]
   [:rationale     :string]])

;------------------------------------------------------------------------------ Layer 0
;; ScanResult — output of the scan phase

(def ScanResult
  "Schema for the result of scanning a repository."
  [:map
   [:violations       [:vector Violation]]
   [:rules-scanned    [:vector :string]]
   [:files-scanned    :int]
   [:scan-duration-ms :int]])

;------------------------------------------------------------------------------ Layer 0
;; PlanTask — one DAG task node

(def PlanTask
  "Schema for a single task in the remediation plan DAG."
  [:map
   [:task/id          uuid?]
   [:task/deps        [:set uuid?]]
   [:task/file        :string]
   [:task/rule-dewey  :string]
   [:task/violations  [:vector Violation]]])

;------------------------------------------------------------------------------ Layer 0
;; PlanSummary — aggregate counts

(def PlanSummary
  "Schema for plan summary statistics."
  [:map
   [:total-violations :int]
   [:auto-fixable     :int]
   [:needs-review     :int]
   [:files-affected   :int]
   [:rules-violated   :int]])

;------------------------------------------------------------------------------ Layer 0
;; Plan — output of the plan phase

(def Plan
  "Schema for the full remediation plan."
  [:map
   [:dag-tasks [:vector PlanTask]]
   [:work-spec :string]
   [:summary   PlanSummary]])

;------------------------------------------------------------------------------ Layer 0
;; DeltaReport — final serialisable output

(def DeltaReport
  "Schema for the delta report written to disk."
  [:map
   [:repo-path       :string]
   [:standards-path  :string]
   [:scan-timestamp  :string]
   [:summary         PlanSummary]
   [:violations      [:vector Violation]]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[malli.core :as m])

  (m/validate Violation
    {:rule/dewey    "210"
     :rule/title    "Clojure Map Access"
     :file          "components/foo/src/ai/miniforge/foo/core.clj"
     :line          42
     :current       "(or (:k m) default)"
     :suggested     "(get m :k default)"
     :auto-fixable? true
     :rationale     "Literal default, non-JSON field"})
  ;; => true

  :leave-this-here)
