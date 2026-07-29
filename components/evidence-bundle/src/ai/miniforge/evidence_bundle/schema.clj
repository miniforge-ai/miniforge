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
  "Root evidence-bundle schema: the complete evidence bundle document, and
   its default template.

   Domain sub-schemas (intent, phase, policy, outcome, provenance, tool,
   pack-promotion, supervision, control-action) live in schema.domain;
   compliance/retention/access-log metadata lives in schema.compliance; the
   optional-key marker and the generic validate-schema engine live in
   schema.optional-key and schema.validation respectively (split out SL003,
   Wave 2 — this file used to hold all of it, at 7 real layers).

   Based on N6 Evidence & Provenance Standard."
  (:require
   [ai.miniforge.evidence-bundle.schema.compliance :as compliance]
   [ai.miniforge.evidence-bundle.schema.optional-key :as optional-key]))

;------------------------------------------------------------------------------ Layer 0

;; Template
(defn ^{:stratum 0} create-evidence-bundle-template
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
   :evidence/data-classification compliance/default-data-classification
   :evidence/contains-pii? false
   :evidence/retention-policy {:retain-days compliance/default-retention-days
                               :auto-delete? true
                               :legal-hold? false}
   :evidence/regulatory-tags #{}
   :evidence/created-by compliance/default-created-by-principal
   :evidence/access-log []})

;; Evidence Bundle Schema
(def ^{:stratum 0} evidence-bundle-schema
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
   (optional-key/optional-key :evidence/plan) map?
   (optional-key/optional-key :evidence/design) map?
   (optional-key/optional-key :evidence/implement) map?
   (optional-key/optional-key :evidence/verify) map?
   (optional-key/optional-key :evidence/review) map?
   (optional-key/optional-key :evidence/release) map?
   (optional-key/optional-key :evidence/observe) map?

   ;; Validation
   (optional-key/optional-key :evidence/semantic-validation) map?
   :evidence/policy-checks vector?
   (optional-key/optional-key :evidence/tool-invocations) vector?

   ;; Pack Promotions (optional, for ETL workflows)
   (optional-key/optional-key :evidence/pack-promotions) vector?

   ;; Supervision Decisions (N6 tool-use evidence)
   (optional-key/optional-key :evidence/supervision-decisions) vector?

   ;; Control Action Evidence
   (optional-key/optional-key :evidence/control-actions) vector?

   ;; Rules Applied (knowledge base rules injected into agents)
   (optional-key/optional-key :evidence/rules-applied) vector?

   ;; Execution Evidence (N11 §9.1)
   (optional-key/optional-key :evidence/execution-mode) keyword?
   (optional-key/optional-key :evidence/runtime-class) keyword?
   (optional-key/optional-key :evidence/task-started-at) inst?
   (optional-key/optional-key :evidence/task-finished-at) inst?
   (optional-key/optional-key :evidence/image-digest) string?

   ;; Dependency Attribution
   (optional-key/optional-key :evidence/dependency-health) map?
   (optional-key/optional-key :evidence/failure-attribution) map?

   ;; Outcome
   :evidence/outcome map?

   ;; Compliance
   (optional-key/optional-key :compliance/sensitive-data) boolean?
   (optional-key/optional-key :compliance/pii-handling) keyword?
   (optional-key/optional-key :compliance/retention-policy) keyword?
   (optional-key/optional-key :compliance/auditor-notes) string?

   ;; Compliance Metadata (extended) — all optional for backwards compatibility.
   (optional-key/optional-key :evidence/data-classification) compliance/valid-data-classification?
   (optional-key/optional-key :evidence/contains-pii?) boolean?
   (optional-key/optional-key :evidence/retention-policy) compliance/valid-retention-policy-map?
   (optional-key/optional-key :evidence/regulatory-tags) compliance/valid-regulatory-tags?
   (optional-key/optional-key :evidence/created-by) string?
   (optional-key/optional-key :evidence/access-log) compliance/valid-access-log?})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (create-evidence-bundle-template)
  evidence-bundle-schema
  :end)
