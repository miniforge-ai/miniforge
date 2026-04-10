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
   Based on N6 Evidence & Provenance Standard.")

;------------------------------------------------------------------------------ Layer 0
;; Schema Utilities

(defrecord OptionalKey [key])

(defn optional-key
  "Mark a schema key as optional."
  [k]
  (->OptionalKey k))

(defn optional-key?
  "Check if a key is marked as optional."
  [k]
  (instance? OptionalKey k))

(defn unwrap-key
  "Unwrap an optional key to get the actual key."
  [k]
  (if (optional-key? k)
    (:key k)
    k))

;; Intent Schema

(def intent-types
  "Valid intent types per N6 spec."
  #{:import :create :update :destroy :refactor :migrate})

(def constraint-schema
  "Schema for intent constraints."
  {:constraint/type keyword?
   :constraint/description string?
   (optional-key :constraint/validation-fn) fn?})

(def intent-schema
  "Schema for intent evidence."
  {:intent/type (fn [t] (contains? intent-types t))
   :intent/description string?
   :intent/business-reason string?
   :intent/constraints (fn [cs] (every? map? cs))
   :intent/declared-at inst?
   (optional-key :intent/author) string?})

;------------------------------------------------------------------------------ Layer 1
;; Phase Evidence Schema

(def phase-result-status-values
  "Valid status values for a phase result in the N6 environment model."
  #{:success :failure :already-implemented :retrying :completed})

(def implement-phase-result-schema
  "Schema for the implement phase result in the N6 environment model.

   Code changes live in the execution environment's git working tree and are
   NOT serialized here. The :environment-id identifies where changes landed;
   :summary is the agent's description of changes made.
   :metrics shape: {:tokens N :duration-ms N}"
  {:status         (fn [s] (contains? phase-result-status-values s))
   :environment-id string?
   :summary        string?
   :metrics        map?})

(def verify-phase-result-schema
  "Schema for the verify phase result in the N6 environment model.

   Test results are captured in :metrics; no serialized code is stored.
   :metrics shape: {:tokens N :duration-ms N :pass-count N :fail-count N
                    :test-output string}"
  {:status         (fn [s] (contains? phase-result-status-values s))
   :environment-id string?
   :summary        string?
   :metrics        map?})

(def release-phase-result-schema
  "Schema for the release phase result in the N6 environment model.

   PR metadata is captured in :metrics. Code provenance is derived from the
   PR diff — the PR URL provides the authoritative record of what changed.
   :metrics shape: {:tokens N :duration-ms N :pr-url string :branch string
                    :commit-sha string}"
  {:status         (fn [s] (contains? phase-result-status-values s))
   :environment-id string?
   :summary        string?
   :metrics        map?})

(def phase-evidence-schema
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

;------------------------------------------------------------------------------ Layer 2
;; Semantic Validation Schema

(def semantic-validation-rules
  "Validation rules per N6 section 2.4.1."
  {:import  {:creates 0 :updates 0 :destroys 0}
   :create  {:creates :pos :updates :any :destroys 0}
   :update  {:creates 0 :updates :pos :destroys 0}
   :destroy {:creates 0 :updates 0 :destroys :pos}
   :refactor {:creates 0 :updates 0 :destroys 0}
   :migrate {:creates :pos :updates 0 :destroys :pos}})

(def semantic-validation-schema
  "Schema for semantic validation evidence."
  {:semantic-validation/declared-intent keyword?
   :semantic-validation/actual-behavior keyword?
   :semantic-validation/resource-creates nat-int?
   :semantic-validation/resource-updates nat-int?
   :semantic-validation/resource-destroys nat-int?
   :semantic-validation/passed? boolean?
   :semantic-validation/violations vector?
   :semantic-validation/checked-at inst?})

;------------------------------------------------------------------------------ Layer 3
;; Policy Check Schema

(def violation-severities
  #{:critical :high :medium :low :info})

(def violation-schema
  "Schema for policy violation."
  {:violation/rule-id string?
   :violation/severity (fn [s] (contains? violation-severities s))
   :violation/message string?
   (optional-key :violation/location) map?
   (optional-key :violation/remediation) string?
   (optional-key :violation/auto-fixable?) boolean?})

(def policy-check-schema
  "Schema for policy check evidence."
  {:policy-check/pack-id string?
   :policy-check/pack-version string?
   :policy-check/phase keyword?
   :policy-check/checked-at inst?
   :policy-check/violations vector?
   :policy-check/passed? boolean?
   :policy-check/duration-ms pos-int?})

;------------------------------------------------------------------------------ Layer 4
;; Outcome Schema

(def pr-statuses #{:open :merged :closed})

(def outcome-schema
  "Schema for workflow outcome."
  {:outcome/success boolean?
   (optional-key :outcome/pr-number) pos-int?
   (optional-key :outcome/pr-url) string?
   (optional-key :outcome/pr-status) (fn [s] (contains? pr-statuses s))
   (optional-key :outcome/pr-merged-at) inst?
   (optional-key :outcome/error-message) string?
   (optional-key :outcome/error-phase) keyword?
   (optional-key :outcome/error-details) map?})

;------------------------------------------------------------------------------ Layer 5
;; Compliance Schema

(def pii-handling-types #{:none :redacted :encrypted})

(def compliance-schema
  "Schema for compliance metadata."
  {:compliance/created-at inst?
   :compliance/sensitive-data boolean?
   :compliance/pii-handling (fn [t] (contains? pii-handling-types t))
   (optional-key :compliance/retention-policy) keyword?
   (optional-key :compliance/auditor-notes) string?})

;------------------------------------------------------------------------------ Layer 5a
;; Rule Applied Schema

(def rule-applied-schema
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

;------------------------------------------------------------------------------ Layer 6
;; Evidence Bundle Schema

(def evidence-bundle-schema
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

   ;; Outcome
   :evidence/outcome map?

   ;; Compliance
   (optional-key :compliance/sensitive-data) boolean?
   (optional-key :compliance/pii-handling) keyword?
   (optional-key :compliance/retention-policy) keyword?
   (optional-key :compliance/auditor-notes) string?})

;------------------------------------------------------------------------------ Layer 7
;; Artifact Provenance Schema

(def provenance-schema
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

(def tool-execution-schema
  "Schema for tool execution record."
  {:tool/name keyword?
   :tool/version string?
   :tool/args map?
   :tool/invoked-at inst?
   :tool/duration-ms pos-int?
   (optional-key :tool/exit-code) int?
   (optional-key :tool/output-summary) string?})

(def tool-invocation-schema
  "Schema for tool invocation record."
  {:tool/id keyword?
   :tool/invoked-at inst?
   :tool/duration-ms nat-int?
   :tool/args map?
   (optional-key :tool/result) (fn [_] true)
   (optional-key :tool/exit-code) int?
   (optional-key :tool/error) map?})

;------------------------------------------------------------------------------ Layer 8
;; Pack Promotion Schema

(def trust-levels
  "Valid trust levels for pack promotion per N6 spec."
  #{:untrusted :tainted :trusted})

(def pack-promotion-schema
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

;------------------------------------------------------------------------------ Layer 9
;; Supervision Decision Schema (N6 tool-use evidence)

(def supervision-decision-schema
  "Schema for individual supervision decision evidence."
  {:supervision/tool-name string?
   :supervision/decision string?
   :supervision/timestamp inst?
   (optional-key :supervision/reasoning) string?
   (optional-key :supervision/meta-eval?) boolean?
   (optional-key :supervision/confidence) float?
   (optional-key :supervision/phase) keyword?})

(def control-action-evidence-schema
  "Schema for control action evidence."
  {:control-action/id uuid?
   :control-action/type keyword?
   :control-action/requester map?
   :control-action/timestamp inst?
   :control-action/result keyword?
   (optional-key :control-action/justification) string?
   (optional-key :control-action/target) map?})

;------------------------------------------------------------------------------ Layer 10
;; Helper Functions

(defn validate-schema
  "Simple schema validation.
   Returns {:valid? bool :errors [...]}"
  [schema data]
  (let [errors (atom [])]
    (doseq [[k validator] schema]
      (let [is-optional? (optional-key? k)
            actual-key (unwrap-key k)
            v (get data actual-key)]
        (cond
          (and (nil? v) (not is-optional?))
          (swap! errors conj {:key actual-key :error "Required key missing"})

          (and v (fn? validator) (not (validator v)))
          (swap! errors conj {:key actual-key :error "Validation failed" :value v}))))
    {:valid? (empty? @errors)
     :errors @errors}))

(defn create-evidence-bundle-template
  "Create an empty evidence bundle template."
  []
  {:evidence-bundle/id (random-uuid)
   :evidence-bundle/workflow-id nil
   :evidence-bundle/created-at (java.time.Instant/now)
   :evidence-bundle/version "1.0.0"
   :evidence/intent {}
   :evidence/policy-checks []
   :evidence/outcome {}
   :evidence/tool-invocations []
   :evidence/pack-promotions []})
