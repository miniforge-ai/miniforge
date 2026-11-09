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
(ns ai.miniforge.qualification.interface
  "Public API for the qualification component: gate evidence with a
   repeatable command and a machine-readable report tied to the build,
   fixture and config hashes, the injected faults and observed state, and
   pass or fail per criterion. A criterion that was not run, or that the
   scenario does not support, is never a pass.

   A gate's scenario namespace calls `run-gate!` from its `-main`, prints
   `summary-lines`, and exits with `exit-code`."
  (:require
   [ai.miniforge.qualification.run :as run]
   [ai.miniforge.qualification.schema :as schema]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

;; Schemas
(def ^{:stratum 0} Gate
  "A gate id, title, optional issue number, and its declared criteria."
  schema/Gate)

(def ^{:stratum 0} ScenarioOutput
  "What a scenario fn returns: plain-data fixtures and config, and one
   CriterionResult per criterion it ran."
  schema/ScenarioOutput)

(def ^{:stratum 0} Report
  "The report `run-gate!` writes."
  schema/Report)

(defn ^{:stratum 0} valid-report?
  "True when `report` is a well-formed qualification report."
  [report]
  (m/validate schema/Report report))

;; Running a gate
(defn ^{:stratum 0} run-gate!
  "Run `scenario` (a no-argument fn returning a ScenarioOutput) for `gate`
   and write the report as EDN and JSON under `:out-dir`. Options:
   `:out-dir` (default \"target/qualification\"), `:command` (the argv
   that repeats the run), `:root` (the workspace whose build is recorded,
   default \".\").

   Returns {:report r :paths {:edn :json}}, {:report r :write-failure
   message}, or {:invalid-gate problems}. A scenario that throws or returns
   malformed output still yields a report: every criterion unrun, verdict
   incomplete."
  [gate scenario opts]
  (run/run-gate! gate scenario opts))

(defn ^{:stratum 0} exit-code
  "0 when the run passed and its report was written, 2 for an invalid gate,
   1 otherwise."
  [result]
  (run/exit-code result))

(defn ^{:stratum 0} summary-lines
  "Human-readable lines for a `run-gate!` result."
  [result]
  (run/summary-lines result))
