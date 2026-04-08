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
  "Malli schemas for policy packs and rules.

   Layer 0: Enums and base types
   Layer 1: Rule component schemas (applicability, detection, enforcement)
   Layer 2: Rule and PackManifest schemas

   Based on policy-pack.spec"
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Enums and base types

(def rule-severities
  "Rule severity levels, ordered from most to least severe."
  [:critical :major :minor :info])

(def RuleSeverity
  "Schema for rule severity enum."
  (into [:enum] rule-severities))

(def enforcement-actions
  "Enforcement actions, ordered from strictest to most lenient."
  [:hard-halt :require-approval :warn :audit])

(def RuleEnforcement
  "Schema for enforcement action enum."
  (into [:enum] enforcement-actions))

(def detection-types
  "Types of violation detection."
  [:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom])

(def DetectionType
  "Schema for detection type enum."
  (into [:enum] detection-types))

(def task-types
  "Task types that rules can apply to."
  [:create :import :modify :delete :migrate])

(def TaskType
  "Schema for task type enum."
  (into [:enum] task-types))

(def repo-types
  "Repository types for rule applicability."
  [:terraform-module :terraform-live :kubernetes :argocd :application])

(def RepoType
  "Schema for repository type enum."
  (into [:enum] repo-types))

(def approver-types
  "Types of approvers for require-approval enforcement."
  [:human :senior-engineer :security])

(def ApproverType
  "Schema for approver type enum."
  (into [:enum] approver-types))

;; Detection and Remediation schemas (for pack-driven compliance scanning)

(def DetectionMode
  "Detection mode: :positive means a match IS a violation,
   :negative means absence of a match IS a violation."
  [:enum :positive :negative])

(def RemediationStrategy
  "How a violation should be remediated."
  [:enum :mechanical :semantic :manual])

(def RemediationType
  "Type of mechanical remediation."
  [:enum :regex-replace :prepend :append])

(def ExcludeContext
  "Context rule that excludes a violation from auto-fixing."
  [:map
   [:path-contains {:optional true} string?]
   [:current-contains {:optional true} [:or string? [:vector string?]]]])

(def RuleRemediation
  "Remediation config for a rule — how to fix violations mechanically."
  [:map
   [:strategy RemediationStrategy]
   [:type {:optional true} RemediationType]
   [:replacement {:optional true} string?]
   [:template {:optional true} string?]
   [:auto-fixable-default {:optional true} boolean?]
   [:exclude-contexts {:optional true} [:vector ExcludeContext]]])

;------------------------------------------------------------------------------ Layer 1
;; Rule component schemas

(def RuleApplicability
  "Schema for when a rule applies.

   All fields are optional - if omitted, rule applies to all matching contexts."
  [:map
   [:task-types {:optional true} [:set TaskType]]
   [:file-globs {:optional true} [:vector string?]]
   [:resource-patterns {:optional true} [:vector [:or string? [:fn {:error/message "should be a regex pattern"} #(instance? java.util.regex.Pattern %)]]]]
   [:repo-types {:optional true} [:set RepoType]]
   [:phases {:optional true} [:set keyword?]]])

(def RuleDetection
  "Schema for how to detect violations.

   Required:
   - :type - Detection mechanism

   Optional:
   - :pattern - Single regex pattern
   - :patterns - Multiple regex patterns
   - :context-lines - Lines of context to include
   - :custom-fn - Symbol for custom detection function"
  [:map
   [:type DetectionType]
   [:pattern {:optional true} [:or string? [:fn {:error/message "should be a regex pattern"} #(instance? java.util.regex.Pattern %)]]]
   [:patterns {:optional true} [:vector [:or string? [:fn {:error/message "should be a regex pattern"} #(instance? java.util.regex.Pattern %)]]]]
   [:context-lines {:optional true} pos-int?]
   [:custom-fn {:optional true} symbol?]
   [:mode {:optional true} DetectionMode]
   [:email-pattern {:optional true} string?]])

(def RuleEnforcementConfig
  "Schema for what happens when a rule is violated.

   Required:
   - :action - The enforcement action to take
   - :message - Human-readable violation message

   Optional:
   - :remediation - Suggested fix
   - :approvers - Required approvers for :require-approval action"
  [:map
   [:action RuleEnforcement]
   [:message string?]
   [:remediation {:optional true} string?]
   [:approvers {:optional true} [:vector ApproverType]]])

(def RuleExample
  "Schema for rule test examples.

   Examples serve as both documentation and test cases."
  [:map
   [:description string?]
   [:input string?]
   [:expected [:enum :pass :fail]]
   [:explanation {:optional true} string?]])

;------------------------------------------------------------------------------ Layer 2
;; Rule and Pack schemas

(def Rule
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
   [:rule/severity RuleSeverity]
   [:rule/category string?]

   ;; When does this rule apply?
   [:rule/applies-to RuleApplicability]

   ;; How to detect violations
   [:rule/detection RuleDetection]

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
   [:rule/enforcement RuleEnforcementConfig]

   ;; Remediation config (pack-driven compliance scanning)
   [:rule/remediation {:optional true} RuleRemediation]

   ;; Examples (for documentation and testing)
   [:rule/examples {:optional true} [:vector RuleExample]]

   ;; Prompt template for LLM-based semantic repair (pack-bundled).
   [:rule/repair-prompt-template {:optional true} string?]

   ;; Enabled flag — when false, rule is skipped during evaluation.
   [:rule/enabled? {:optional true} boolean?]

   ;; Metadata
   [:rule/version {:optional true} string?]
   [:rule/author {:optional true} string?]
   [:rule/references {:optional true} [:vector string?]]])

(def PackCategory
  "Schema for a category within a pack."
  [:map
   [:category/id string?]
   [:category/name string?]
   [:category/rules [:vector keyword?]]])

(def PackDependency
  "Schema for pack extension/dependency."
  [:map
   [:pack-id string?]
   [:version-constraint {:optional true} string?]])

(def TrustLevel
  "Schema for pack trust levels (N1 §2.10.2).
   - :tainted   - Flagged by scanners; MUST NOT be used for instruction
   - :untrusted - Repo-derived or external; data-only unless promoted
   - :trusted   - Platform-validated and/or user-promoted"
  [:enum :tainted :untrusted :trusted])

(def AuthorityChannel
  "Schema for pack authority channels (N1 §2.10.2).
   - :authority/instruction - May shape agent plans (requires :trusted)
   - :authority/data        - Reference material only (any trust level)"
  [:enum :authority/instruction :authority/data])

(def TaxonomyRef
  "Schema for a taxonomy reference within a pack manifest.
   Packs declare which taxonomy they target and the minimum version required."
  [:map
   [:taxonomy/id keyword?]
   [:taxonomy/min-version string?]])

(def PackOverride
  "Schema for an overlay pack rule override.
   Only :rule/severity and :rule/enabled? are overridable per N4 spec."
  [:map
   [:rule/id keyword?]
   [:rule/severity {:optional true} RuleSeverity]
   [:rule/enabled? {:optional true} boolean?]])

(def PackManifest
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
   [:pack/trust-level {:optional true} TrustLevel]
   [:pack/authority {:optional true} AuthorityChannel]

   ;; Taxonomy reference (N4 §2.1)
   [:pack/taxonomy-ref {:optional true} TaxonomyRef]

   ;; Pack-bundled prompt templates (N4 §6).
   ;; Keys: :behavior-section, :knowledge-section, :repair-prompt
   ;; Templates use {{variable}} interpolation.
   [:pack/prompt-templates {:optional true} [:map-of :keyword string?]]

   ;; Config overrides (governance config tuning from trusted packs)
   [:pack/config-overrides {:optional true} [:map-of :keyword :map]]

   ;; Dependencies
   [:pack/extends {:optional true} [:vector PackDependency]]

   ;; Rule overrides (overlay packs — N4 §2.5)
   [:pack/overrides {:optional true} [:vector PackOverride]]

   ;; Organization
   [:pack/categories [:vector PackCategory]]

   ;; The actual rules
   [:pack/rules [:vector Rule]]

   ;; Timestamps
   [:pack/created-at inst?]
   [:pack/updated-at inst?]
   [:pack/changelog {:optional true} string?]])

;------------------------------------------------------------------------------ Layer 2
;; Validation helpers

(defn valid?
  "Check if value validates against schema."
  [schema value]
  (m/validate schema value))

(defn validate
  "Validate value against schema.
   Returns {:valid? bool :errors map-or-nil}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

(defn explain
  "Return human-readable explanation of validation errors, or nil if valid."
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

(defn valid-rule?
  "Check if value is a valid Rule."
  [value]
  (valid? Rule value))

(defn validate-rule
  "Validate a rule. Returns {:valid? bool :errors ...}."
  [value]
  (validate Rule value))

(defn valid-pack?
  "Check if value is a valid PackManifest."
  [value]
  (valid? PackManifest value))

(defn validate-pack
  "Validate a pack manifest. Returns {:valid? bool :errors ...}."
  [value]
  (validate PackManifest value))

;------------------------------------------------------------------------------ Layer 2
;; Result helpers (used by loader.clj)

(defn succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

(defn success
  "Create a success result.
   (success :pack pack {:errors nil}) => {:success? true :pack pack :errors nil}"
  [key value extras]
  (merge {:success? true key value} extras))

(defn failure
  "Create a failure result.
   (failure :data \"error msg\") => {:success? false :error \"error msg\"}"
  [_key message]
  {:success? false :error message})

(defn failure-with-errors
  "Create a failure result with error list.
   (failure-with-errors :pack [...]) => {:success? false :errors [...]}"
  [_key errors]
  {:success? false :errors errors})

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
  (explain Rule {:rule/id "not-a-keyword"})

  :leave-this-here)
