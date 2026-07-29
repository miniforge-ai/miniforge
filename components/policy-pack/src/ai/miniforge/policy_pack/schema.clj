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
(ns ai.miniforge.policy-pack.schema
  "Malli schemas for the top-level policy-pack artifacts: an individual
   Rule and the PackManifest that collects them, plus their validators.

   Was over the rule 210 budget at 6 real layers (Wave 2, SL003); split into
   three namespaces along the real composition chain:
   - `schema-types.clj` — base enums and the component schemas
     (RuleApplicability, RuleDetection, RuleEnforcementConfig, etc.) Rule is
     built from
   - `schema-validation.clj` — generic Malli valid?/validate/explain plus the
     {:success? ...} result-map helpers
   - `schema.clj` (this file) — Rule and PackManifest themselves, and their
     valid-*?/validate-* wrappers

   Layer 0: Rule (composes schema-types/RuleApplicability,
     .../RuleDetection, .../RuleRemediation, .../RuleExample,
     .../RuleEnforcementConfig, .../RuleSeverity — all external, no
     same-file dependents)
   Layer 1: PackManifest (composes Rule), valid-rule?, validate-rule (each
     over Rule + schema-validation, external)
   Layer 2: valid-pack?, validate-pack (over PackManifest + schema-validation)

   Based on policy-pack.spec"
  (:require
   [ai.miniforge.policy-pack.schema-types :as schema-types]
   [ai.miniforge.policy-pack.schema-validation :as schema-validation]))

;------------------------------------------------------------------------------ Layer 0

;; Rule and Pack schemas
(def ^{:stratum 0} Rule
  "Schema for an individual policy rule.

   Rules define:
   - What to check (applicability)
   - How to detect violations (detection)
   - What to do when violated (enforcement)
   - Examples for testing and documentation"
  [:map
   ;; Identity
   [:rule/id keyword?]
   [:rule/title string?]
   [:rule/description string?]
   [:rule/severity schema-types/RuleSeverity]
   [:rule/category string?]

   ;; When does this rule apply?
   [:rule/applies-to schema-types/RuleApplicability]

   ;; How to detect violations
   [:rule/detection schema-types/RuleDetection]

   ;; Agent guidance (critical for correct interpretation)
   [:rule/agent-behavior {:optional true} string?]

   ;; Knowledge content — full rule text for reference material injection.
   ;; Distinct from :rule/agent-behavior (concise directive). This is the
   ;; detailed explanation extracted from the MDC body.
   [:rule/knowledge-content {:optional true} string?]

   ;; Always-inject flag — when true, rule is pre-injected into every agent
   ;; prompt for applicable phases (phase-only gating, bypasses file-glob and
   ;; task-type context matching). When false/omitted, available for on-demand
   ;; query or full-context matching only.
   [:rule/always-inject? {:optional true} boolean?]

   ;; What happens when violated
   [:rule/enforcement schema-types/RuleEnforcementConfig]

   ;; Remediation config (pack-driven compliance scanning)
   [:rule/remediation {:optional true} schema-types/RuleRemediation]

   ;; Examples (for documentation and testing)
   [:rule/examples {:optional true} [:vector schema-types/RuleExample]]

   ;; Prompt template for LLM-based semantic repair (pack-bundled).
   [:rule/repair-prompt-template {:optional true} string?]

   ;; Enabled flag — when false, rule is skipped during evaluation.
   [:rule/enabled? {:optional true} boolean?]

   ;; Metadata
   [:rule/version {:optional true} string?]
   [:rule/author {:optional true} string?]
   [:rule/references {:optional true} [:vector string?]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PackManifest
  "Schema for a policy pack manifest.

   Packs are versioned collections of rules that can be:
   - Authored and shared
   - Extended from other packs
   - Signed for trust (paid feature)

   Trust model (N1 §2.10.2):
   - Trust level determines if content can be used for instruction
   - Authority channel determines how content may be used
   - Transitive trust rules prevent trust escalation"
  [:map
   ;; Identity
   [:pack/id string?]
   [:pack/name string?]
   [:pack/version string?]
   [:pack/description string?]
   [:pack/author string?]

   ;; Optional metadata
   [:pack/license {:optional true} string?]
   [:pack/homepage {:optional true} string?]
   [:pack/repository {:optional true} string?]

   ;; Signing (paid feature)
   [:pack/signature {:optional true} string?]
   [:pack/signed-by {:optional true} string?]
   [:pack/signed-at {:optional true} inst?]

   ;; Trust and authority (N1 §2.10.2)
   [:pack/trust-level {:optional true} schema-types/TrustLevel]
   [:pack/authority {:optional true} schema-types/AuthorityChannel]

   ;; Taxonomy reference (N4 §2.1)
   [:pack/taxonomy-ref {:optional true} schema-types/TaxonomyRef]

   ;; Pack-bundled prompt templates (N4 §6).
   ;; Keys: :behavior-section, :knowledge-section, :repair-prompt
   ;; Templates use {{variable}} interpolation.
   [:pack/prompt-templates {:optional true} [:map-of :keyword string?]]

   ;; Config overrides (governance config tuning from trusted packs)
   [:pack/config-overrides {:optional true} [:map-of :keyword :map]]

   ;; Dependencies
   [:pack/extends {:optional true} [:vector schema-types/PackDependency]]

   ;; Rule overrides (overlay packs — N4 §2.5)
   [:pack/overrides {:optional true} [:vector schema-types/PackOverride]]

   ;; Organization
   [:pack/categories [:vector schema-types/PackCategory]]

   ;; The actual rules
   [:pack/rules [:vector Rule]]

   ;; Timestamps
   [:pack/created-at inst?]
   [:pack/updated-at inst?]
   [:pack/changelog {:optional true} string?]])

(defn ^{:stratum 1} valid-rule?
  [value]
  (schema-validation/valid? Rule value))

(defn ^{:stratum 1} validate-rule
  [value]
  (schema-validation/validate Rule value))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} valid-pack?
  [value]
  (schema-validation/valid? PackManifest value))

(defn ^{:stratum 2} validate-pack
  [value]
  (schema-validation/validate PackManifest value))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate a rule
  (valid-rule?
   {:rule/id :310-import-block-preservation
    :rule/title "Preserve import blocks"
    :rule/description "Never remove import blocks during IMPORT tasks"
    :rule/severity :critical
    :rule/category "310"
    :rule/applies-to {:task-types #{:import}
                      :file-globs ["**/*.tf"]}
    :rule/detection {:type :diff-analysis
                     :pattern "^-\\s*import\\s*\\{"}
    :rule/enforcement {:action :hard-halt
                       :message "Cannot remove import blocks"}})
  ;; => true

  ;; Validate a knowledge rule with new fields
  (valid-rule?
   {:rule/id :std/stratified-design
    :rule/title "Stratified Design"
    :rule/description "Engineering standard (001): Stratified Design"
    :rule/severity :info
    :rule/category "001"
    :rule/applies-to {:phases #{:plan :implement :review :verify :release}}
    :rule/detection {:type :custom}
    :rule/enforcement {:action :audit :message "Standard: Stratified Design"}
    :rule/agent-behavior "Before writing code, output a stratified plan."
    :rule/knowledge-content "# Stratified Design\n\nFull body text..."
    :rule/always-inject? true})
  ;; => true

  ;; Validate a pack
  (validate-pack
   {:pack/id "test-pack"
    :pack/name "Test Pack"
    :pack/version "2026.01.22"
    :pack/description "A test pack"
    :pack/author "test"
    :pack/categories []
    :pack/rules []
    :pack/created-at (java.time.Instant/now)
    :pack/updated-at (java.time.Instant/now)})

  ;; Get validation errors
  (schema-validation/explain Rule {:rule/id "not-a-keyword"})

  :leave-this-here)
