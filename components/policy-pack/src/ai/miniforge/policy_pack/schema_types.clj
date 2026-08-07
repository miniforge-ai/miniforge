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
(ns ai.miniforge.policy-pack.schema-types
  "Base enums and the component Malli schemas composed from them — the
   building blocks `schema/Rule` and `schema/PackManifest` are assembled
   from.

   Split out of schema.clj (Wave 2, SL003): everything below the Rule/
   PackManifest composition line lives here, so schema.clj itself only has
   to lay out the two top-level schemas plus their validators.

   Layer 0: Enums and base types (severities, enforcement-actions,
     detection-types, task/repo/approver-types, DetectionMode,
     RemediationStrategy/Type, ExcludeContext, RuleExample, PackCategory,
     PackDependency, TrustLevel, AuthorityChannel, TaxonomyRef) — pure data,
     no same-file dependents
   Layer 1: RuleEnforcement, DetectionType, TaskType, RepoType, ApproverType,
     RuleRemediation, PackOverride (each composes Layer 0 enums/types)
   Layer 2: RuleApplicability, RuleDetection, RuleEnforcementConfig (compose
     Layer 1 schemas)

   Based on policy-pack.spec"
  (:require
   [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

;; Enums and base types
(def ^{:stratum 0} rule-severities
  "Rule severity levels, ordered from most to least severe. Aliases the shared
   canonical `schema/severities` — a rule's severity is the same axis as a
   runtime violation's, so one scale (see schema.core)."
  shared/severities)

(def ^{:stratum 0} RuleSeverity
  "Schema for rule severity enum — the shared canonical `schema/Severity`."
  shared/Severity)

(def ^{:stratum 0} enforcement-actions
  "Enforcement actions, ordered from strictest to most lenient. Defined by
   the policy-clause component (Ariadne step 1a); passed through here so
   pack consumers keep one import site."
  shared/enforcement-actions)

(def ^{:stratum 0} detection-types
  "Types of violation detection."
  [:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom :capability])

(def ^{:stratum 0} task-types
  "Task types that rules can apply to."
  [:create :import :modify :delete :migrate])

(def ^{:stratum 0} repo-types
  "Repository types for rule applicability."
  [:terraform-module :terraform-live :kubernetes :argocd :application])

(def ^{:stratum 0} approver-types
  "Types of approvers for require-approval enforcement."
  [:human :senior-engineer :security])

;; Detection and Remediation schemas (for pack-driven compliance scanning)
(def ^{:stratum 0} DetectionMode
  "Detection mode: :positive means a match IS a violation,
   :negative means absence of a match IS a violation."
  [:enum :positive :negative])

(def ^{:stratum 0} RemediationStrategy
  "How a violation should be remediated."
  [:enum :mechanical :semantic :manual])

(def ^{:stratum 0} RemediationType
  "Type of mechanical remediation."
  [:enum :regex-replace :prepend :append])

(def ^{:stratum 0} ExcludeContext
  "Context rule that excludes a violation from auto-fixing."
  [:map
   [:path-contains {:optional true} string?]
   [:current-contains {:optional true} [:or string? [:vector string?]]]])

(def ^{:stratum 0} RuleExample
  "Schema for rule test examples.

   Examples serve as both documentation and test cases."
  [:map
   [:description string?]
   [:input string?]
   [:expected [:enum :pass :fail]]
   [:explanation {:optional true} string?]])

(def ^{:stratum 0} PackCategory
  "Schema for a category within a pack."
  [:map
   [:category/id string?]
   [:category/name string?]
   [:category/rules [:vector keyword?]]])

(def ^{:stratum 0} PackDependency
  "Schema for pack extension/dependency."
  [:map
   [:pack-id string?]
   [:version-constraint {:optional true} string?]])

(def ^{:stratum 0} TrustLevel
  "Schema for pack trust levels (N1 §2.10.2).
   - :tainted   - Flagged by scanners; MUST NOT be used for instruction
   - :untrusted - Repo-derived or external; data-only unless promoted
   - :trusted   - Platform-validated and/or user-promoted"
  [:enum :tainted :untrusted :trusted])

(def ^{:stratum 0} AuthorityChannel
  "Schema for pack authority channels (N1 §2.10.2).
   - :authority/instruction - May shape agent plans (requires :trusted)
   - :authority/data        - Reference material only (any trust level)"
  [:enum :authority/instruction :authority/data])

(def ^{:stratum 0} TaxonomyRef
  "Schema for a taxonomy reference within a pack manifest.
   Packs declare which taxonomy they target and the minimum version required."
  [:map
   [:taxonomy/id keyword?]
   [:taxonomy/min-version string?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} RuleEnforcement
  "Schema for enforcement action enum."
  (into [:enum] enforcement-actions))

(def ^{:stratum 1} DetectionType
  "Schema for detection type enum."
  (into [:enum] detection-types))

(def ^{:stratum 1} TaskType
  "Schema for task type enum."
  (into [:enum] task-types))

(def ^{:stratum 1} RepoType
  "Schema for repository type enum."
  (into [:enum] repo-types))

(def ^{:stratum 1} ApproverType
  "Schema for approver type enum."
  (into [:enum] approver-types))

(def ^{:stratum 1} RuleRemediation
  "Remediation config for a rule — how to fix violations mechanically."
  [:map
   [:strategy RemediationStrategy]
   [:type {:optional true} RemediationType]
   [:replacement {:optional true} string?]
   [:template {:optional true} string?]
   [:auto-fixable-default {:optional true} boolean?]
   [:exclude-contexts {:optional true} [:vector ExcludeContext]]])

(def ^{:stratum 1} PackOverride
  "Schema for an overlay pack rule override.
   Only :rule/severity and :rule/enabled? are overridable per N4 spec."
  [:map
   [:rule/id keyword?]
   [:rule/severity {:optional true} RuleSeverity]
   [:rule/enabled? {:optional true} boolean?]])

;------------------------------------------------------------------------------ Layer 2

;; Rule component schemas
(def ^{:stratum 2} RuleApplicability
  "Schema for when a rule applies.

   All fields are optional - if omitted, rule applies to all matching contexts."
  [:map
   [:task-types {:optional true} [:set TaskType]]
   [:file-globs {:optional true} [:vector string?]]
   [:resource-patterns {:optional true} [:vector [:or string? [:fn {:error/message "should be a regex pattern"} #(instance? java.util.regex.Pattern %)]]]]
   [:repo-types {:optional true} [:set RepoType]]
   [:phases {:optional true} [:set keyword?]]])

(def ^{:stratum 2} RuleDetection
  "Schema for how to detect violations.

   Required:
   - :type - Detection mechanism

   Optional:
   - :pattern - Single regex pattern
   - :patterns - Multiple regex patterns
   - :context-lines - Lines of context to include
   - :custom-fn - Symbol for custom detection function
   - :capability - Keyword naming a registered mechanical-check capability
     (used with :type :capability; resolved via the capability registry)
   - :mode - :positive (default; a match is a violation) or :negative
     (the ABSENCE of a match is a violation, e.g. a required header)
   - :multiline? - when true, match patterns against the whole content instead
     of line-by-line (for patterns that span lines)
   - :detector-config - a data map of policy parameters for a :custom detector
     (e.g. an approved-values list), so the policy stays data instead of being
     baked into a regex. The detector reads it via :policy-pack/rule in context.
   - :email-pattern - secondary regex (e.g. a copyright-header email check)"
  [:map
   [:type DetectionType]
   [:pattern {:optional true} [:or string? [:fn {:error/message "should be a regex pattern"} #(instance? java.util.regex.Pattern %)]]]
   [:patterns {:optional true} [:vector [:or string? [:fn {:error/message "should be a regex pattern"} #(instance? java.util.regex.Pattern %)]]]]
   [:context-lines {:optional true} pos-int?]
   [:custom-fn {:optional true} symbol?]
   [:capability {:optional true} keyword?]
   [:mode {:optional true} DetectionMode]
   [:multiline? {:optional true} boolean?]
   [:detector-config {:optional true} [:map-of keyword? any?]]
   [:email-pattern {:optional true} string?]])

(def ^{:stratum 2} RuleEnforcementConfig
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

;------------------------------------------------------------------------------ Rich Comment
(comment
  (into [:enum] enforcement-actions)
  ;; => [:enum :hard-halt :require-approval :warn :audit]

  :leave-this-here)
