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
(ns ai.miniforge.evidence-bundle.schema.domain
  "Domain evidence schemas per the N6 Evidence & Provenance Standard.

   Intent, phase results, policy checks and violations, outcome, artifact
   provenance, tool execution, pack promotion, supervision decisions, control
   actions, and knowledge-rule application — everything the evidence bundle
   captures about a workflow's execution, distinct from the
   compliance/retention subset in schema.compliance.

   Split out of the former `schema.clj` (SL003, Wave 2) — this was most of
   its Layer 0/1/2."
  (:require
   [ai.miniforge.evidence-bundle.schema.control-action :as control-action]
   [ai.miniforge.evidence-bundle.semantic-rules :as semantic]
   [ai.miniforge.evidence-bundle.schema.optional-key :as optional-key]
   [ai.miniforge.evidence-bundle.schema.outcome-reliability :as outcome]
   [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} constraint-records? [value]
  (and (vector? value) (every? map? value)))

(defn- ^{:stratum 0} artifact-ids? [value]
  (and (vector? value) (every? uuid? value)))

(defn- ^{:stratum 0} event-stream-range? [value]
  (and (map? value) (nat-int? (:start-seq value)) (nat-int? (:end-seq value))
       (<= (:start-seq value) (:end-seq value))))

;; Intent Schema
(def ^{:stratum 0} intent-types
  "Valid intent types per N6 spec."
  #{:import :create :update :destroy :refactor :migrate})

(def ^{:stratum 0} semantic-validation-rules
  "Validation rules per N6 section 2.4.1."
  semantic/rules)

(defn- ^{:stratum 0} resource-counts [value]
  {:creates (:semantic-validation/resource-creates value)
   :updates (:semantic-validation/resource-updates value)
   :destroys (:semantic-validation/resource-destroys value)})

(def ^{:stratum 0} semantic-validation-schema
  "Schema for semantic validation evidence."
  {:semantic-validation/declared-intent keyword?
   :semantic-validation/actual-behavior keyword?
   :semantic-validation/resource-creates nat-int?
   :semantic-validation/resource-updates nat-int?
   :semantic-validation/resource-destroys nat-int?
   :semantic-validation/passed? boolean?
   :semantic-validation/violations vector?
   :semantic-validation/checked-at inst?})

(def ^{:stratum 0} constraint-schema
  "Schema for intent constraints."
  {:constraint/type keyword?
   :constraint/description string?
   (optional-key/optional-key :constraint/validation-fn) fn?})

;; Phase Evidence Schema
(def ^{:stratum 0} phase-result-status-values
  "Valid status values for a phase result in the N6 environment model."
  #{:success :failure :already-implemented :retrying :completed})

;; Policy Check Schema
(def ^{:stratum 0} policy-check-schema
  "Schema for policy check evidence."
  {:policy-check/pack-id string?
   :policy-check/pack-version string?
   :policy-check/phase keyword?
   :policy-check/checked-at inst?
   :policy-check/violations vector?
   :policy-check/passed? boolean?
   ;; nat-int?: evidence collection defaults a missing duration to 0
   ;; (build-policy-check-evidence), which pos-int? would reject
   :policy-check/duration-ms nat-int?
   ;; nilable: legacy/mechanical gate results carry no DecisionEnvelope —
   ;; the collector always writes the key, with nil for envelope-less checks
   (optional-key/optional-key :policy-check/envelope) (some-fn nil? map?)})

(def ^{:stratum 0} violation-severities
  "Pass-through to the canonical severity scale (policy-clause via the
   shared schema interface) — the local copy is gone."
  (set shared/severities))

;; Outcome Schema
(def ^{:stratum 0} pr-statuses #{:open :merged :closed})

;; Rule Applied Schema
(def ^{:stratum 0} rule-applied-schema
  "Schema for a single rule-applied entry in the evidence bundle.
   Captures which knowledge base rules were injected into agent context.
   Keys match the manifest shape returned by knowledge store's
   compute-manifest-entry, with :phase added by the collector."
  {:id uuid?
   :title string?
   :role keyword?
   :tags-matched vector?
   :score number?
   :phase keyword?})

;; Pack Promotion Schema
(def ^{:stratum 0} trust-levels
  "Valid trust levels for pack promotion per N6 spec."
  #{:untrusted :tainted :trusted})

(def ^{:stratum 0} tool-execution-schema
  "Schema for tool execution record."
  {:tool/name keyword?
   :tool/version string?
   :tool/args map?
   :tool/invoked-at inst?
   :tool/duration-ms pos-int?
   (optional-key/optional-key :tool/exit-code) int?
   (optional-key/optional-key :tool/output-summary) string?})

(def ^{:stratum 0} tool-invocation-schema
  "Schema for tool invocation record."
  {:tool/id keyword?
   :tool/invoked-at inst?
   :tool/duration-ms nat-int?
   :tool/args map?
   (optional-key/optional-key :tool/result) (fn [_] true)
   (optional-key/optional-key :tool/exit-code) int?
   (optional-key/optional-key :tool/error) map?})

;; Supervision Decision Schema (N6 tool-use evidence)
(def ^{:stratum 0} supervision-decision-schema
  "Schema for individual supervision decision evidence."
  {:supervision/tool-name string?
   :supervision/decision string?
   :supervision/timestamp inst?
   (optional-key/optional-key :supervision/reasoning) string?
   (optional-key/optional-key :supervision/meta-eval?) boolean?
   (optional-key/optional-key :supervision/confidence) number?
   (optional-key/optional-key :supervision/phase) keyword?})

(def ^{:stratum 0} control-action-evidence-schema
  "Schema for control action evidence."
  {:action/id uuid?
   :action/type keyword?
   :action/requester control-action/requester?
   :action/timestamp inst?
   :action/result control-action/result?
   (optional-key/optional-key :action/justification) string?
   (optional-key/optional-key :action/target) map?
   (optional-key/optional-key :action/parameters) map?
   (optional-key/optional-key :action/approval) control-action/approval?
   (optional-key/optional-key :action/pre-state) map?
   (optional-key/optional-key :action/post-state) map?})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} phase-output-schema
  "Collected phase output is a projection, not the enclosing execution result.
   Validate known fields when present; phase-specific output remains extensible."
  {(optional-key/optional-key :environment-id) string?
   (optional-key/optional-key :status) (partial contains? phase-result-status-values)
   (optional-key/optional-key :summary) string?
   (optional-key/optional-key :metrics) map?})

(defn- ^{:stratum 1} failed-rule-ids [value]
  (let [failed (semantic/failed-rules (:semantic-validation/declared-intent value)
                                     (resource-counts value))]
    (set (map semantic/rule-ids failed))))

(def ^{:stratum 1} phase-evidence-schema
  "Schema for individual phase evidence."
  {:phase/name keyword?
   :phase/agent keyword?
   :phase/agent-instance-id uuid?
   :phase/started-at inst?
   :phase/completed-at inst?
   :phase/duration-ms nat-int?
   :phase/output map?
   :phase/artifacts artifact-ids?
   (optional-key/optional-key :phase/inner-loop-iterations) nat-int?
   (optional-key/optional-key :phase/event-stream-range) event-stream-range?})

;; Artifact Provenance Schema
(def ^{:stratum 1} provenance-schema
  "Schema for artifact provenance per N6 section 3.2."
  {:provenance/workflow-id uuid?
   :provenance/phase keyword?
   :provenance/agent keyword?
   :provenance/agent-instance-id uuid?
   :provenance/created-at inst?
   (optional-key/optional-key :provenance/created-by-event-id) uuid?
   (optional-key/optional-key :provenance/source-artifacts) artifact-ids?
   (optional-key/optional-key :provenance/tool-executions) vector?
   :provenance/content-hash string?
   (optional-key/optional-key :provenance/signature) string?})

(def ^{:stratum 1} intent-schema
  "Schema for intent evidence."
  {:intent/type (fn [t] (contains? intent-types t))
   :intent/description string?
   :intent/business-reason string?
   :intent/constraints constraint-records?
   :intent/declared-at inst?
   (optional-key/optional-key :intent/author) string?})

(def ^{:stratum 1} implement-phase-result-schema
  "Schema for the implement phase result in the N6 environment model.

   Code changes live in the execution environment's git working tree and are
   NOT serialized here. The :environment-id identifies where changes landed;
   :summary is the agent's description of changes made.
   :metrics shape: {:tokens N :duration-ms N}"
  {:status         (fn [s] (contains? phase-result-status-values s))
   :environment-id string?
   :summary        string?
   :metrics        map?})

(def ^{:stratum 1} verify-phase-result-schema
  "Schema for the verify phase result in the N6 environment model.

   Test results are captured in :metrics; no serialized code is stored.
   :metrics shape: {:tokens N :duration-ms N :pass-count N :fail-count N
                    :test-output string}"
  {:status         (fn [s] (contains? phase-result-status-values s))
   :environment-id string?
   :summary        string?
   :metrics        map?})

(def ^{:stratum 1} release-phase-result-schema
  "Schema for the release phase result in the N6 environment model.

   PR metadata is captured in :metrics. Code provenance is derived from the
   PR diff — the PR URL provides the authoritative record of what changed.
   :metrics shape: {:tokens N :duration-ms N :pr-url string :branch string
                    :commit-sha string}"
  {:status         (fn [s] (contains? phase-result-status-values s))
   :environment-id string?
   :summary        string?
   :metrics        map?})

(def ^{:stratum 1} violation-schema
  "Schema for policy violation."
  {:violation/rule-id string?
   :violation/severity (fn [s] (contains? violation-severities s))
   :violation/message string?
   (optional-key/optional-key :violation/location) map?
   (optional-key/optional-key :violation/remediation) string?
   (optional-key/optional-key :violation/auto-fixable?) boolean?})

(def ^{:stratum 1} outcome-schema
  "Schema for workflow outcome."
  {:outcome/success boolean?
   (optional-key/optional-key :outcome/failure-class) outcome/failure-class?
   (optional-key/optional-key :outcome/tier) outcome/tier?
   (optional-key/optional-key :outcome/degradation-mode) outcome/degradation-mode?
   (optional-key/optional-key :outcome/sli-measurements) outcome/sli-measurements?
   (optional-key/optional-key :outcome/pr-number) pos-int?
   (optional-key/optional-key :outcome/pr-url) string?
   (optional-key/optional-key :outcome/pr-status) (fn [s] (contains? pr-statuses s))
   (optional-key/optional-key :outcome/pr-merged-at) inst?
   (optional-key/optional-key :outcome/error-message) string?
   (optional-key/optional-key :outcome/error-phase) keyword?
   (optional-key/optional-key :outcome/error-details) map?})

(def ^{:stratum 1} pack-promotion-schema
  "Schema for pack promotion evidence per N6 section 2.1."
  {:pack/id string?
   :pack/type keyword?
   :from-trust (fn [t] (contains? trust-levels t))
   :to-trust (fn [t] (contains? trust-levels t))
   :promoted-by string?
   :promoted-at inst?
   :promotion-policy string?
   :promotion-justification string?  ; REQUIRED: audit trail for trust decision
   :pack-hash string?
   (optional-key/optional-key :pack-signature) string?})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} consistent-semantic-conclusion? [value]
  (let [failed (failed-rule-ids value)
        reported (set (map :violation/rule-id (:semantic-validation/violations value)))
        actual (:semantic-validation/actual-behavior value)
        inferred (semantic/inferred-behavior (resource-counts value))]
    (and (= (empty? failed) (:semantic-validation/passed? value))
         (or (= inferred actual) (= [:import :refactor] [inferred actual]))
         (= failed reported))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  intent-schema
  phase-evidence-schema
  violation-schema
  outcome-schema
  pack-promotion-schema
  :end)
