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
(ns ai.miniforge.policy-clause.interface
  "Public API for the policy-clause component: the single definition
   site for the policy severity scale and the enforcement-action
   vocabulary (Ariadne step 1a). Unknown values are anomalies, never
   defaults."
  (:require
   [ai.miniforge.policy-clause.core :as core]
   [ai.miniforge.policy-clause.vocabulary :as voc]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} severities
  "Canonical policy severity levels, most to least severe."
  voc/severities)

(def ^{:stratum 0} severity-order
  "Severity -> rank, 0 = most severe (unchecked lookup)."
  voc/severity-order)

(def ^{:stratum 0} enforcement-actions
  "Enforcement actions, strictest to most lenient (relaxation modes)."
  voc/enforcement-actions)

(def ^{:stratum 0} enforcement-order
  "Enforcement action -> rank, 0 = strictest."
  voc/enforcement-order)

(def ^{:stratum 0} mechanical-severities
  "The mechanical gate scale — a different axis; see the mapping fns."
  voc/mechanical-severities)

(def ^{:stratum 0} normalize-severity
  "Coerce legacy `:major`/`:minor` to `:high`/`:low`."
  voc/normalize-severity)

(def ^{:stratum 0} known-severity?
  "True when the value (after normalization) is on the scale."
  voc/known-severity?)

(def ^{:stratum 0} known-enforcement-action?
  "True when the value is in the enforcement vocabulary."
  voc/known-enforcement-action?)

(def ^{:stratum 0} rank-severity
  "Checked rank (0 = most severe) or an `:unknown-severity` anomaly."
  core/rank-severity)

(def ^{:stratum 0} compare-severity
  "Checked comparator; anomaly when either severity is unknown."
  core/compare-severity)

(def ^{:stratum 0} more-severe
  "The more severe of two severities, or an anomaly."
  core/more-severe)

(def ^{:stratum 0} sort-by-severity
  "Most-severe-first sort with up-front validation."
  core/sort-by-severity)

(def ^{:stratum 0} mechanical->severity
  "Mechanical gate value -> policy severity; anomaly for `:none`/unknown."
  core/mechanical->severity)

(def ^{:stratum 0} severity->attention
  "Policy severity -> attention scale (:critical/:warning/:info); anomaly for unknown."
  core/severity->attention)

(def ^{:stratum 0} severity->mechanical
  "Policy severity -> mechanical gate value; anomaly for unknown."
  core/severity->mechanical)

(def ^{:stratum 0} Severity
  "Malli enum for a canonical policy severity."
  voc/Severity)

(def ^{:stratum 0} EnforcementAction
  "Malli enum for an enforcement action."
  voc/EnforcementAction)
