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

(ns ai.miniforge.workbench-adapter.schema
  "Emit-side Malli schemas for the Minibench workbench wire shapes this
   adapter produces: `workbench_snapshot/v1` and `state_var_registry/v1`.

   These are an INDEPENDENT Apache-licensed spelling of the documented
   workbench wire format — this component deliberately does NOT depend on
   the (proprietary) workbench-contract mirror. The canonical contract is
   the Rust `workbench-contract` repo; its golden fixtures round-trip a
   miniforge-emitted snapshot/registry in its own test suite, which is
   the cross-repo gate that keeps this spelling honest.

   Emission schemas are CLOSED: a misspelled optional key fails here
   instead of being silently dropped by the consumer's parser.")

;; ---------------------------------------------------------------- wire enums

(def StateStatus
  [:enum "pass" "warn" "fail" "blocked" "not_applicable" "unknown"])

(def state-var-kinds
  ["presence" "integrity" "sufficiency" "alignment" "compliance"
   "quality" "regression" "budget" "privacy"])

;; ---------------------------------------------------------------- evaluation

(def EvidenceRef
  [:map {:closed true}
   [:id :string]
   [:source_role :string]
   [:artifact_id {:optional true} :string]
   [:quote {:optional true} :string]])

(def Finding
  [:map {:closed true}
   [:severity {:optional true} :string]
   [:message [:string {:min 1}]]])

(def StateEvaluation
  [:map {:closed true}
   [:state_var_id :string]
   [:product [:= "miniforge"]]
   [:status StateStatus]
   [:value {:optional true} any?]
   [:score number?]
   [:confidence number?]
   [:score_components {:optional true} [:map-of :keyword number?]]
   [:evidence_refs [:vector EvidenceRef]]
   [:findings [:vector Finding]]
   [:gate_effect :string]
   [:evaluated_at :string]])

;; ---------------------------------------------------------------- snapshot

(def RunVariant
  [:map {:closed false}
   [:experiment_id :string]
   [:label :string]
   [:model {:optional true} :string]
   [:method {:optional true} :string]
   [:axes {:optional true} [:map-of :keyword :string]]])

(def WorkbenchSnapshot
  [:map {:closed true}
   [:schema_version [:= "workbench_snapshot/v1"]]
   [:snapshot_id :string]
   [:generated_at :string]
   [:product [:= "miniforge"]]
   [:run_id :string]
   [:variant {:optional true} RunVariant]
   [:registry_ref [:map {:closed true}
                   [:registry_id :string]
                   [:version :string]]]
   [:source_hashes {:optional true} [:vector :string]]
   [:evaluations [:vector StateEvaluation]]
   [:entities [:map-of :keyword any?]]
   [:metadata {:optional true} any?]
   [:signature [:map {:closed true}
                [:algorithm :string]
                [:digest :string]
                [:canonicalization :string]]]])

;; ---------------------------------------------------------------- registry

(def StateVariable
  [:map {:closed true}
   [:id :string]
   [:version :string]
   [:product [:= "miniforge"]]
   [:area :string]
   [:kind (into [:enum] state-var-kinds)]
   [:description [:string {:min 1}]]
   [:value_type [:enum "boolean" "number" "string" "enum" "object" "array"]]
   [:thresholds [:map-of :keyword number?]]
   [:evidence_requirements [:map {:closed true}
                            [:required_refs [:vector :string]]]]
   [:score_components [:vector :string]]
   [:gate_effects [:map-of :keyword :string]]
   [:lifecycle [:enum "draft" "active" "deprecated" "experimental"]]
   [:owner {:optional true} :string]
   [:notes {:optional true} :string]])

(def StateVarRegistry
  [:map {:closed true}
   [:schema_version [:= "state_var_registry/v1"]]
   [:registry_id :string]
   [:version :string]
   [:product [:= "miniforge"]]
   [:state_vars [:vector StateVariable]]])
