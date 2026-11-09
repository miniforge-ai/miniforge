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
   machine-readable report tied to the build, fixture and config hashes,
   the injected faults and observed state, and pass or fail per criterion.
   A criterion that was not run, or that the scenario does not support, is
   never a pass."
  (:require
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
  "A qualification report."
  schema/Report)

(defn ^{:stratum 0} valid-report?
  "True when `report` is a well-formed qualification report."
  [report]
  (m/validate schema/Report report))
