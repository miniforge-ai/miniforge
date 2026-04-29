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

(ns ai.miniforge.compliance-scanner.interface
  "Public API for the compliance-scanner component.

   Thin pass-throughs only — no implementation logic here.

   Layer 0: Schema re-exports
   Layer 1: Phase entry points
   Layer 2: Convenience run! orchestrator"
  (:require [ai.miniforge.compliance-scanner.schema             :as schema]
            [ai.miniforge.compliance-scanner.factory            :as factory]
            [ai.miniforge.compliance-scanner.scan               :as scan]
            [ai.miniforge.compliance-scanner.classify           :as classify]
            [ai.miniforge.compliance-scanner.plan               :as plan]
            [ai.miniforge.compliance-scanner.report             :as report]
            [ai.miniforge.compliance-scanner.execute            :as execute]
            [ai.miniforge.compliance-scanner.exceptions-as-data :as exc-data]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def Violation   schema/Violation)
(def ScanResult  schema/ScanResult)
(def PlanTask    schema/PlanTask)
(def Plan        schema/Plan)
(def PlanSummary schema/PlanSummary)

;------------------------------------------------------------------------------ Layer 0
;; Factory re-exports

(def ->violation factory/->violation)
(def ->scan-result factory/->scan-result)
(def ->plan-summary factory/->plan-summary)
(def ->plan-task factory/->plan-task)
(def ->plan factory/->plan)
(def ->delta-report factory/->delta-report)
(def DeltaReport schema/DeltaReport)

;------------------------------------------------------------------------------ Layer 0
;; Built-in linter re-exports
;;
;; Standalone Clojure-AST linters that the compliance scanner runs in
;; addition to the regex-driven pack rules.

(def exceptions-as-data-rule-id
  "Stable rule id for the exceptions-as-data linter."
  exc-data/rule-id)

(defn scan-exceptions-as-data
  "Run the exceptions-as-data linter against `repo-root`. Returns a map
   with `:violations`, `:files-scanned`, `:counts`, and `:rule/id`."
  [repo-root]
  (exc-data/scan-repo repo-root))

;------------------------------------------------------------------------------ Layer 1
;; Phase entry points

(defn scan
  "Scan a repo for violations across all configured rules.

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - opts           - map with :rules (default :all), :since (git ref, optional)

   Returns ScanResult map."
  [repo-path standards-path opts]
  (scan/scan-repo repo-path standards-path opts))

(defn classify
  "Add :auto-fixable? and :rationale to each violation.

   Arguments:
   - violations - vector of raw Violation maps from scan

   Returns updated violation vector."
  [violations]
  (classify/classify-violations violations))

(defn plan
  "Generate DAG task defs and markdown work spec from classified violations.

   Arguments:
   - violations - vector of classified Violation maps
   - repo-path  - string path to repo root

   Returns Plan map."
  [violations repo-path]
  (plan/plan violations repo-path))

(defn write-report!
  "Write EDN delta report to .miniforge/compliance-report.edn.

   Arguments:
   - delta-report - DeltaReport map
   - repo-path    - string path to repo root

   Returns the path written."
  [delta-report repo-path]
  (report/write-report! delta-report repo-path))

(defn write-work-spec!
  "Write markdown work spec to docs/compliance/YYYY-MM-DD-compliance-delta.md.

   Arguments:
   - work-spec - markdown string
   - repo-path - string path to repo root

   Returns the path written."
  [work-spec repo-path]
  (report/write-work-spec! work-spec repo-path))

(defn write-delta-report!
  "Build and write EDN delta report from classified violations and plan.

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - classified     - vector of classified Violation maps (from classify phase)
   - plan           - Plan map (for :summary)

   Returns the path written."
  [repo-path standards-path classified plan]
  (let [timestamp    (.toString (java.time.Instant/now))
        delta-report (factory/->delta-report repo-path standards-path timestamp
                                             (:summary plan) classified)]
    (write-report! delta-report repo-path)))

(defn execute!
  "Apply all auto-fixable violations and create one PR per rule.

   Arguments:
   - plan      - Plan map with :dag-tasks (from the plan phase)
   - repo-path - string path to the repo/worktree root

   Returns map with :prs (vector of per-rule results), :violations-fixed, :files-changed."
  [plan repo-path]
  (execute/execute! plan repo-path))

;------------------------------------------------------------------------------ Layer 2
;; Convenience orchestrator

(defn compliance-run!
  "Convenience: scan → classify → plan → write-report! → write-work-spec!

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - opts           - map with :rules (default :all), :since (optional)

   Returns Plan map (also writes reports to disk)."
  [repo-path standards-path opts]
  (let [scan-result  (scan repo-path standards-path opts)
        classified   (classify (:violations scan-result))
        the-plan     (plan classified repo-path)
        timestamp    (.toString (java.time.Instant/now))
        delta-report (factory/->delta-report
                      repo-path
                      standards-path
                      timestamp
                      (:summary the-plan)
                      classified)]
    (write-report! delta-report repo-path)
    (write-work-spec! (:work-spec the-plan) repo-path)
    the-plan))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Scan the current repo with all rules
  (def result (scan "." ".standards" {:rules :all}))
  (count (:violations result))

  ;; Classify and plan
  (def classified (classify (:violations result)))
  (def the-plan (plan classified "."))
  (println (:work-spec the-plan))

  ;; Full pipeline
  (def p (compliance-run! "." ".standards" {:rules :all}))
  (:summary p)

  :leave-this-here)
