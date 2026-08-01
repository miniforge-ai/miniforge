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
(ns ai.miniforge.compliance-scanner.plan
  "Plan phase: generate DAG task defs and markdown work spec from violations.

   DAG-task assembly, per-rule section rendering, and markdown work-spec
   assembly live in `plan-dag`, `plan-rule-section`, and `plan-work-spec`
   respectively (rule 210: a fifth real layer here is the signal to
   split it).

   Layer 0: Top-level plan entry point"
  (:require [ai.miniforge.compliance-scanner.factory        :as factory]
            [ai.miniforge.compliance-scanner.plan-dag        :as plan-dag]
            [ai.miniforge.compliance-scanner.plan-work-spec  :as work-spec]))

;------------------------------------------------------------------------------ Layer 0

;; Top-level entry point
(defn ^{:stratum 0} plan
  "Generate DAG task defs and markdown work spec from violations.

   Arguments:
   - violations  - vector of classified Violation maps
   - _repo-path  - string path to repo root (reserved for future use)

   Returns Plan map."
  [violations _repo-path]
  (let [dag-tasks    (plan-dag/build-dag-tasks violations)
        summary      (factory/->plan-summary violations)
        work-spec-md (work-spec/build-work-spec violations summary)]
    (factory/->plan dag-tasks work-spec-md summary)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def viols
    [{:rule/id :std/clojure :rule/category "210" :rule/title "Clojure Map Access"
      :file "components/foo/src/core.clj" :line 10
      :current "(get m :k nil)" :suggested "(get m :k nil)"
      :auto-fixable? true :rationale "Literal default"}
     {:rule/id :std/header-copyright :rule/category "810" :rule/title "Copyright Header (Markdown)"
      :file "components/foo/src/core.clj" :line 1
      :current "(missing copyright header)" :suggested nil
      :auto-fixable? true :rationale "Header absent"}
     {:rule/id :std/clojure :rule/category "210" :rule/title "Clojure Map Access"
      :file "components/bar/src/core.clj" :line 5
      :current "(get m :status :ok)" :suggested "(get m :status :ok)"
      :auto-fixable? false :rationale "Possible JSON-mapped field"}])

  (def p (plan viols "."))
  (println (:work-spec p))
  (count (:dag-tasks p))

  :leave-this-here)
