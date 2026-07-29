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
(ns ai.miniforge.policy-pack.interface.schema
  "Schema and validation helpers for the policy-pack public API."
  (:require
   [ai.miniforge.policy-pack.schema :as schema]
   [ai.miniforge.policy-pack.schema-types :as schema-types]))

;------------------------------------------------------------------------------ Layer 0

;; Schema and validation re-exports
(def ^{:stratum 0} Rule
  "Malli schema (a `[:map ...]` vector) for an individual policy rule —
   identity, applicability, detection, enforcement, and optional knowledge,
   remediation, and example fields."
  schema/Rule)

(def ^{:stratum 0} PackManifest
  "Malli schema (a `[:map ...]` vector) for a policy pack manifest — a
   versioned collection of rules with identity, trust/authority, taxonomy
   reference, dependencies, overrides, categories, and timestamps."
  schema/PackManifest)

(def ^{:stratum 0} RuleSeverity
  "Malli enum schema (`[:enum :critical :high :medium :low :info]`) for a rule's
   severity level."
  schema-types/RuleSeverity)

(def ^{:stratum 0} RuleEnforcement
  "Malli enum schema (`[:enum :hard-halt :require-approval :warn :audit]`) for
   a rule's enforcement action."
  schema-types/RuleEnforcement)

(def ^{:stratum 0} RuleApplicability
  "Malli schema (a `[:map ...]` vector) for when a rule applies — optional
   task-types, file-globs, resource-patterns, repo-types, and phases."
  schema-types/RuleApplicability)

(def ^{:stratum 0} RuleDetection
  "Malli schema (a `[:map ...]` vector) for how to detect violations —
   required :type plus optional pattern(s), context-lines, custom-fn,
   capability, mode, and email-pattern."
  schema-types/RuleDetection)

(def ^{:stratum 0} rule-severities
  "Vector of severity keywords `[:critical :high :medium :low :info]`, ordered most
   to least severe."
  schema-types/rule-severities)

(def ^{:stratum 0} enforcement-actions
  "Vector of enforcement keywords `[:hard-halt :require-approval :warn :audit]`,
   ordered strictest to most lenient."
  schema-types/enforcement-actions)

(def ^{:stratum 0} detection-types
  "Vector of detection-type keywords
   `[:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis
   :custom :capability]`."
  schema-types/detection-types)

(def ^{:stratum 0} valid-rule?
  "Predicate. Returns true when the value validates against the Rule schema,
   false otherwise."
  schema/valid-rule?)

(def ^{:stratum 0} validate-rule
  "Validate a rule against the Rule schema. Returns {:valid? bool :errors
   map-or-nil} (humanized Malli errors when invalid)."
  schema/validate-rule)

(def ^{:stratum 0} valid-pack?
  "Predicate. Returns true when the value validates against the PackManifest
   schema, false otherwise."
  schema/valid-pack?)

(def ^{:stratum 0} validate-pack
  "Validate a pack manifest against the PackManifest schema. Returns
   {:valid? bool :errors map-or-nil} (humanized Malli errors when invalid)."
  schema/validate-pack)
