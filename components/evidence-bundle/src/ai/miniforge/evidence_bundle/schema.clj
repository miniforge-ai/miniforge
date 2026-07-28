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
(ns ai.miniforge.evidence-bundle.schema
  "Schema definitions for evidence bundles and related structures.
   Based on N6 Evidence & Provenance Standard."
  (:require
   [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

;; Schema Utilities
(defrecord ^{:stratum 0} OptionalKey [key])

;; Intent Schema
(def ^{:stratum 0} intent-types
  "Valid intent types per N6 spec."
  #{:import :create :update :destroy :refactor :migrate})

;; Phase Evidence Schema
(def ^{:stratum 0} phase-result-status-values
  "Valid status values for a phase result in the N6 environment model."
  #{:success :failure :already-implemented :retrying :completed})

;; Semantic Validation Schema
(def ^{:stratum 0} semantic-validation-rules
  "Validation rules per N6 section 2.4.1."
  {:import  {:creates 0 :updates 0 :destroys 0}
   :create  {:creates :pos :updates :any :destroys 0}
   :update  {:creates 0 :updates :pos :destroys 0}
   :destroy {:creates 0 :updates 0 :destroys :pos}
   :refactor {:creates 0 :updates 0 :destroys 0}
   :migrate {:creates :pos :updates 0 :destroys :pos}})

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

;; Policy Check Schema
(def ^{:stratum 0} violation-severities
  "Pass-through to the canonical severity scale (policy-clause via the
   shared schema interface) — the local copy is gone."
  (set shared/severities))

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
   :policy-check/envelope (some-fn nil? map?)})

;; Outcome Schema
(def ^{:stratum 0} pr-statuses #{:open :merged :closed})

;; Compliance Schema
(def ^{:stratum 0} pii-handling-types #{:none :redacted :encrypted})

(def ^{:stratum 0} data-classifications
  "Valid data classification levels for evidence bundles."
  #{:public :internal :confidential :restricted})

(def ^{:stratum 0} regulatory-tag-values
  "Regulatory frameworks that may apply to an evidence bundle."
  #{:gdpr :hipaa :sox :pci})

(def ^{:stratum 0} default-retention-days
  "Default retention period for evidence bundles — 90 days aligns with the
   team's baseline audit window. Callers can override per-bundle retention
   through :evidence/retention-policy in workflow-spec or opts compliance
   metadata for regulatory environments that require longer holds."
  90)

(def ^{:stratum 0} default-data-classification
  "Default data classification for new evidence bundles. :internal rather
   than :public ensures new bundles are never accidentally treated as safe
   to share externally; operators promoting to :public must explicitly opt in.
   :confidential and :restricted require manual assignment at bundle creation."
  :internal)

(def ^{:stratum 0} default-created-by-principal
  "Default :evidence/created-by value for system-generated bundles.
   The string 'system' is the canonical principal token for automated evidence
   collection, distinct from a human user identity."
  "system")

(def ^{:stratum 0} retention-policy-schema
  "Schema for retention policy sub-document."
  {:retain-days pos-int?
   :auto-delete? boolean?
   :legal-hold? boolean?})

;------------------------------------------------------------------------------ Layer 5a
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} optional-key
  "Mark a schema key as optional."
  [k]
  (->OptionalKey k))

(defn ^{:stratum 1} optional-key?
  "Check if a key is marked as optional."
  [k]
  (instance? OptionalKey k))

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

;------------------------------------------------------------------------------ Layer 5b
;; Compliance Validators
;; Named fns — not anonymous — so they can be tested independently and
;; referenced by name in evidence-bundle-schema (rule 002: no anon fns in maps).
(defn- ^{:stratum 1} valid-data-classification?
  "Returns true when v is a member of data-classifications."
  [v]
  (contains? data-classifications v))

(defn- ^{:stratum 1} valid-regulatory-tags?
  "Returns true when v is a set containing only known regulatory tags."
  [v]
  (and (set? v)
       (every? #(contains? regulatory-tag-values %) v)))

;; Template
(defn ^{:stratum 1} create-evidence-bundle-template
  "Create an empty evidence bundle template with default compliance metadata."
  []
  {:evidence-bundle/id (random-uuid)
   :evidence-bundle/workflow-id nil
   :evidence-bundle/created-at (java.time.Instant/now)
   :evidence-bundle/version "1.0.0"
   :evidence/intent {}
   :evidence/policy-checks []
   :evidence/outcome {}
   :evidence/tool-invocations []
   :evidence/pack-promotions []
   :evidence/dependency-health {}
   :evidence/data-classification default-data-classification
   :evidence/contains-pii? false
   :evidence/retention-policy {:retain-days default-retention-days
                               :auto-delete? true
                               :legal-hold? false}
   :evidence/regulatory-tags #{}
   :evidence/created-by default-created-by-principal
   :evidence/access-log []})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unwrap-key
  "Unwrap an optional key to get the actual key."
  [k]
  (if (optional-key? k)
    (:key k)
    k))

(def ^{:stratum 2} constraint-schema
  "Schema for intent constraints."
  {:constraint/type keyword?
   :constraint/description string?
   (optional-key :constraint/validation-fn) fn?})

(def ^{:stratum 2} intent-schema
  "Schema for intent evidence."
  {:intent/type (fn [t] (contains? intent-types t))
   :intent/description string?
   :intent/business-reason string?
   :intent/constraints (fn [cs] (every? map? cs))
   :intent/declared-at inst?
   (optional-key :intent/author) string?})

(def ^{:stratum 2} phase-evidence-schema
  "Schema for individual phase evidence."
  {:phase/name keyword?
   :phase/agent keyword?
   :phase/agent-instance-id uuid?
   :phase/started-at inst?
   :phase/completed-at inst?
   :phase/duration-ms pos-int?
   :phase/output map?
   :phase/artifacts (fn [as] (every? uuid? as))
   (optional-key :phase/inner-loop-iterations) pos-int?
   (optional-key :phase/event-stream-range) map?})

(def ^{:stratum 2} violation-schema
  "Schema for policy violation."
  {:violation/rule-id string?
   :violation/severity (fn [s] (contains? violation-severities s))
   :violation/message string?
   (optional-key :violation/location) map?
   (optional-key :violation/remediation) string?
   (optional-key :violation/auto-fixable?) boolean?})

(def ^{:stratum 2} outcome-schema
  "Schema for workflow outcome."
  {:outcome/success boolean?
   (optional-key :outcome/pr-number) pos-int?
   (optional-key :outcome/pr-url) string?
   (optional-key :outcome/pr-status) (fn [s] (contains? pr-statuses s))
   (optional-key :outcome/pr-merged-at) inst?
   (optional-key :outcome/error-message) string?
   (optional-key :outcome/error-phase) keyword?
   (optional-key :outcome/error-details) map?})

(def ^{:stratum 2} compliance-schema
  "Schema for compliance metadata."
  {:compliance/created-at inst?
   :compliance/sensitive-data boolean?
   :compliance/pii-handling (fn [t] (contains? pii-handling-types t))
   (optional-key :compliance/retention-policy) keyword?
   (optional-key :compliance/auditor-notes) string?})

(def ^{:stratum 2} access-log-entry-schema
  "Schema for a single access log entry on an evidence bundle."
  {:access-log/principal string?
   :access-log/action keyword?
   :access-log/timestamp inst?
   (optional-key :access-log/reason) string?})

;; Artifact Provenance Schema
(def ^{:stratum 2} provenance-schema
  "Schema for artifact provenance per N6 section 3.2."
  {:provenance/workflow-id uuid?
   :provenance/phase keyword?
   :provenance/agent keyword?
   :provenance/agent-instance-id uuid?
   :provenance/created-at inst?
   (optional-key :provenance/created-by-event-id) uuid?
   (optional-key :provenance/source-artifacts) (fn [as] (every? uuid? as))
   (optional-key :provenance/tool-executions) vector?
   :provenance/content-hash string?
   (optional-key :provenance/signature) string?})

(def ^{:stratum 2} tool-execution-schema
  "Schema for tool execution record."
  {:tool/name keyword?
   :tool/version string?
   :tool/args map?
   :tool/invoked-at inst?
   :tool/duration-ms pos-int?
   (optional-key :tool/exit-code) int?
   (optional-key :tool/output-summary) string?})

(def ^{:stratum 2} tool-invocation-schema
  "Schema for tool invocation record."
  {:tool/id keyword?
   :tool/invoked-at inst?
   :tool/duration-ms nat-int?
   :tool/args map?
   (optional-key :tool/result) (fn [_] true)
   (optional-key :tool/exit-code) int?
   (optional-key :tool/error) map?})

(def ^{:stratum 2} pack-promotion-schema
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
   (optional-key :pack-signature) string?})

;; Supervision Decision Schema (N6 tool-use evidence)
(def ^{:stratum 2} supervision-decision-schema
  "Schema for individual supervision decision evidence."
  {:supervision/tool-name string?
   :supervision/decision string?
   :supervision/timestamp inst?
   (optional-key :supervision/reasoning) string?
   (optional-key :supervision/meta-eval?) boolean?
   (optional-key :supervision/confidence) float?
   (optional-key :supervision/phase) keyword?})

(def ^{:stratum 2} control-action-evidence-schema
  "Schema for control action evidence."
  {:control-action/id uuid?
   :control-action/type keyword?
   :control-action/requester map?
   :control-action/timestamp inst?
   :control-action/result keyword?
   (optional-key :control-action/justification) string?
   (optional-key :control-action/target) map?})

;------------------------------------------------------------------------------ Layer 3

;------------------------------------------------------------------------------ Layer 0b
;; Schema Validation Helper
;; Defined early so Layer 5+ schemas can reference it in named validator fns.
(defn ^{:stratum 3} validate-schema
  "Simple schema validation.
   Returns {:valid? bool :errors [...]}"
  [schema data]
  (let [errors (atom [])]
    (doseq [[k validator] schema]
      (let [is-optional? (optional-key? k)
            actual-key   (unwrap-key k)
            v            (get data actual-key)]
        (cond
          (and (nil? v) (not is-optional?))
          (swap! errors conj {:key actual-key :error "Required key missing"})

          (and (some? v) (fn? validator) (not (validator v)))
          (swap! errors conj {:key actual-key :error "Validation failed" :value v}))))
    {:valid? (empty? @errors)
     :errors @errors}))

;------------------------------------------------------------------------------ Layer 4

(defn- ^{:stratum 4} valid-retention-policy-map?
  "Returns true when v is a map that satisfies retention-policy-schema."
  [v]
  (and (map? v)
       (:valid? (validate-schema retention-policy-schema v))))

(defn- ^{:stratum 4} valid-access-log-entry?
  "Returns true when a single access log entry satisfies access-log-entry-schema."
  [entry]
  (:valid? (validate-schema access-log-entry-schema entry)))

;------------------------------------------------------------------------------ Layer 5

(defn- ^{:stratum 5} valid-access-log?
  "Returns true when the access log is a vector of valid entries."
  [v]
  (and (vector? v)
       (every? valid-access-log-entry? v)))

;------------------------------------------------------------------------------ Layer 6

;; Evidence Bundle Schema
(def ^{:stratum 6} evidence-bundle-schema
  "Complete schema for evidence bundle per N6 spec."
  {:evidence-bundle/id uuid?
   :evidence-bundle/workflow-id uuid?
   :evidence-bundle/created-at inst?
   :evidence-bundle/version string?

   ;; Intent
   :evidence/intent map?

   ;; Phase Evidence (only for executed phases)
   ;; :evidence/implement — summary + metrics (no :code/files)
   ;; :evidence/verify   — test output in :phase/output :metrics
   ;; :evidence/release  — PR metadata in :phase/output :metrics; code from PR diff
   (optional-key :evidence/plan) map?
   (optional-key :evidence/design) map?
   (optional-key :evidence/implement) map?
   (optional-key :evidence/verify) map?
   (optional-key :evidence/review) map?
   (optional-key :evidence/release) map?
   (optional-key :evidence/observe) map?

   ;; Validation
   (optional-key :evidence/semantic-validation) map?
   :evidence/policy-checks vector?
   (optional-key :evidence/tool-invocations) vector?

   ;; Pack Promotions (optional, for ETL workflows)
   (optional-key :evidence/pack-promotions) vector?

   ;; Supervision Decisions (N6 tool-use evidence)
   (optional-key :evidence/supervision-decisions) vector?

   ;; Control Action Evidence
   (optional-key :evidence/control-actions) vector?

   ;; Rules Applied (knowledge base rules injected into agents)
   (optional-key :evidence/rules-applied) vector?

   ;; Execution Evidence (N11 §9.1)
   (optional-key :evidence/execution-mode) keyword?
   (optional-key :evidence/runtime-class) keyword?
   (optional-key :evidence/task-started-at) inst?
   (optional-key :evidence/task-finished-at) inst?
   (optional-key :evidence/image-digest) string?

   ;; Dependency Attribution
   (optional-key :evidence/dependency-health) map?
   (optional-key :evidence/failure-attribution) map?

   ;; Outcome
   :evidence/outcome map?

   ;; Compliance
   (optional-key :compliance/sensitive-data) boolean?
   (optional-key :compliance/pii-handling) keyword?
   (optional-key :compliance/retention-policy) keyword?
   (optional-key :compliance/auditor-notes) string?

   ;; Compliance Metadata (extended) — all optional for backwards compatibility.
   (optional-key :evidence/data-classification) valid-data-classification?
   (optional-key :evidence/contains-pii?) boolean?
   (optional-key :evidence/retention-policy) valid-retention-policy-map?
   (optional-key :evidence/regulatory-tags) valid-regulatory-tags?
   (optional-key :evidence/created-by) string?
   (optional-key :evidence/access-log) valid-access-log?})
