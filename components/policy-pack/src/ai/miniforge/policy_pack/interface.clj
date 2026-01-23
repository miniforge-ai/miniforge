;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.policy-pack.interface
  "Public API for the policy-pack component.

   Provides policy-as-code functionality:
   - Pack and rule schemas
   - Pack registry for CRUD and composition
   - Pack loading from EDN files and directories
   - Rule violation detection
   - Artifact checking against packs

   Layer 0: Schema re-exports
   Layer 1: Registry operations
   Layer 2: Loading operations
   Layer 3: Detection and checking
   Layer 4: Rule and pack builders"
  (:require
   [ai.miniforge.policy-pack.schema :as schema]
   [ai.miniforge.policy-pack.registry :as registry]
   [ai.miniforge.policy-pack.loader :as loader]
   [ai.miniforge.policy-pack.detection :as detection]
   [ai.miniforge.policy-pack.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def Rule
  "Malli schema for a policy rule."
  schema/Rule)

(def PackManifest
  "Malli schema for a policy pack manifest."
  schema/PackManifest)

(def RuleSeverity
  "Malli schema for rule severity enum."
  schema/RuleSeverity)

(def RuleEnforcement
  "Malli schema for enforcement action enum."
  schema/RuleEnforcement)

(def RuleApplicability
  "Malli schema for rule applicability config."
  schema/RuleApplicability)

(def RuleDetection
  "Malli schema for rule detection config."
  schema/RuleDetection)

;; Enum values for programmatic access
(def rule-severities
  "Rule severity levels: [:critical :major :minor :info]"
  schema/rule-severities)

(def enforcement-actions
  "Enforcement actions: [:hard-halt :require-approval :warn :audit]"
  schema/enforcement-actions)

(def detection-types
  "Detection types: [:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom]"
  schema/detection-types)

;------------------------------------------------------------------------------ Layer 0
;; Validation functions

(def valid-rule?
  "Check if value is a valid Rule.
   Returns boolean."
  schema/valid-rule?)

(def validate-rule
  "Validate a rule against the schema.
   Returns {:valid? bool :errors map-or-nil}."
  schema/validate-rule)

(def valid-pack?
  "Check if value is a valid PackManifest.
   Returns boolean."
  schema/valid-pack?)

(def validate-pack
  "Validate a pack manifest against the schema.
   Returns {:valid? bool :errors map-or-nil}."
  schema/validate-pack)

;------------------------------------------------------------------------------ Layer 1
;; Registry protocol re-export

(def PolicyPackRegistry
  "Protocol for managing policy packs.

   Methods:
   - register-pack [this pack]
   - get-pack [this pack-id]
   - get-pack-version [this pack-id version]
   - list-packs [this criteria]
   - delete-pack [this pack-id version]
   - import-pack [this source]
   - export-pack [this pack-id version format]
   - validate-pack [this pack]
   - verify-signature [this pack]
   - resolve-pack [this pack-id]
   - get-rules-for-context [this pack-ids context]"
  registry/PolicyPackRegistry)

(def create-registry
  "Create an in-memory policy pack registry.

   Options:
   - :logger - Logger instance for structured logging

   Example:
     (create-registry)
     (create-registry {:logger my-logger})"
  registry/create-registry)

;; Registry operations delegated
(defn register-pack
  "Register a pack in the registry.
   Returns the registered pack."
  [registry pack]
  (registry/register-pack registry pack))

(defn get-pack
  "Get latest version of a pack by ID.
   Returns pack or nil."
  [registry pack-id]
  (registry/get-pack registry pack-id))

(defn get-pack-version
  "Get specific version of a pack.
   Returns pack or nil."
  [registry pack-id version]
  (registry/get-pack-version registry pack-id version))

(defn list-packs
  "List packs matching criteria.
   Criteria: {:category :author :search}"
  [registry criteria]
  (registry/list-packs registry criteria))

(defn delete-pack
  "Delete a pack version from registry.
   Returns true if deleted."
  [registry pack-id version]
  (registry/delete-pack registry pack-id version))

(defn resolve-pack
  "Resolve a pack with all extensions merged.
   Returns fully resolved pack."
  [registry pack-id]
  (registry/resolve-pack registry pack-id))

(defn get-rules-for-context
  "Get applicable rules for pack IDs and context.
   Returns vector of applicable rules."
  [registry pack-ids context]
  (registry/get-rules-for-context registry pack-ids context))

;------------------------------------------------------------------------------ Layer 2
;; Loading operations

(def load-pack
  "Load a policy pack, auto-detecting format.

   Supports:
   - Single EDN file (pack.edn or *.pack.edn)
   - Directory with pack.edn manifest

   Arguments:
   - path - File or directory path

   Returns:
   - {:success? bool :pack PackManifest :errors [...]}

   Example:
     (load-pack \"terraform-safety.pack.edn\")
     (load-pack \"./packs/terraform-safety/\")"
  loader/load-pack)

(def load-pack-from-file
  "Load a policy pack from a single EDN file.

   Arguments:
   - file-path - Path to the .pack.edn file

   Returns:
   - {:success? bool :pack PackManifest :errors [...]}

   Example:
     (load-pack-from-file \"terraform-safety.pack.edn\")"
  loader/load-pack-from-file)

(def load-pack-from-directory
  "Load a policy pack from a directory structure.

   Expected structure:
   ```
   my-pack/
   ├── pack.edn           # Pack manifest
   └── rules/             # Optional separate rule files
       └── ...
   ```

   Arguments:
   - dir-path - Path to the pack directory

   Returns:
   - {:success? bool :pack PackManifest :errors [...]}

   Example:
     (load-pack-from-directory \"./packs/terraform-safety\")"
  loader/load-pack-from-directory)

(def discover-packs
  "Discover all packs in a directory.

   Arguments:
   - packs-dir - Directory containing packs

   Returns:
   - Vector of {:path string :type :file|:directory}"
  loader/discover-packs)

(def load-all-packs
  "Load all packs from a packs directory.

   Arguments:
   - packs-dir - Directory containing packs

   Returns:
   - {:loaded [PackManifest...] :failed [{:path :errors}...]}

   Example:
     (load-all-packs \".miniforge/packs\")"
  loader/load-all-packs)

(def write-pack-to-file
  "Write a pack manifest to a single EDN file.

   Arguments:
   - pack - PackManifest
   - file-path - Output file path

   Returns:
   - {:success? bool :error string}"
  loader/write-pack-to-file)

;------------------------------------------------------------------------------ Layer 3
;; Detection and checking

(def detect-violation
  "Detect violations for a rule against an artifact.

   Dispatches to the appropriate detection implementation based on
   the rule's :rule/detection :type.

   Arguments:
   - rule - Rule with :rule/detection
   - artifact - Artifact being validated
   - context - Execution context map

   Returns:
   - Violation map if detected, nil otherwise."
  detection/detect-violation)

(def check-rules
  "Check multiple rules against an artifact.

   Arguments:
   - rules - Vector of rules to check
   - artifact - Artifact being validated
   - context - Execution context

   Returns:
   - Vector of violation maps"
  detection/check-rules)

(def check-artifact
  "Check an artifact against all applicable rules from pack(s).

   Arguments:
   - pack - PackManifest (or vector of packs)
   - artifact - Artifact to check
   - context - Execution context

   Returns:
   - {:passed? bool
      :violations [...]
      :blocking [...]
      :require-approval [...]
      :warnings [...]
      :audits [...]}

   Example:
     (check-artifact pack
                     {:artifact/content \"...\" :artifact/path \"main.tf\"}
                     {:phase :implement})"
  core/check-artifact)

(def check-artifacts
  "Check multiple artifacts against pack rules.

   Arguments:
   - pack - PackManifest (or vector of packs)
   - artifacts - Vector of artifacts
   - context - Execution context

   Returns:
   - Vector of check results, one per artifact"
  core/check-artifacts)

;; Violation classification helpers
(def blocking-violations
  "Filter violations that block progress (:hard-halt)."
  detection/blocking-violations)

(def approval-required-violations
  "Filter violations requiring approval."
  detection/approval-required-violations)

(def warning-violations
  "Filter warning-only violations."
  detection/warning-violations)

(def violation->error
  "Convert violation to gate error map."
  detection/violation->error)

(def violation->warning
  "Convert violation to gate warning map."
  detection/violation->warning)

;------------------------------------------------------------------------------ Layer 4
;; Rule and pack builders

(def create-pack
  "Create a new policy pack with default values.

   Arguments:
   - id - Pack ID string
   - name - Human-readable name
   - description - Pack description
   - author - Author string

   Options:
   - :version - DateVer string (default: today's date)
   - :license - License string
   - :rules - Vector of rules

   Returns:
   - PackManifest

   Example:
     (create-pack \"my-pack\" \"My Pack\" \"Description\" \"author\"
                  :license \"Apache-2.0\"
                  :rules [rule1 rule2])"
  core/create-pack)

(def create-rule
  "Create a new rule with required fields.

   Arguments:
   - id - Rule ID keyword
   - title - Short title
   - description - Full description
   - severity - :critical, :major, :minor, or :info
   - category - Dewey category string
   - detection - Detection config map
   - enforcement - Enforcement config map

   Options:
   - :applies-to - Applicability config
   - :agent-behavior - Agent guidance string
   - :examples - Vector of example maps

   Returns:
   - Rule map

   Example:
     (create-rule :no-todos
                  \"No TODOs\"
                  \"Forbid TODO comments in production code\"
                  :minor \"800\"
                  (content-scan-detection \"TODO\")
                  (warn-enforcement \"Found TODO comment\"))"
  core/create-rule)

(def add-rule-to-pack
  "Add a rule to a pack. Returns updated pack."
  core/add-rule-to-pack)

(def remove-rule-from-pack
  "Remove a rule from a pack by ID. Returns updated pack."
  core/remove-rule-from-pack)

(def update-pack-categories
  "Regenerate pack categories from rules."
  core/update-pack-categories)

;; Detection config builders
(def content-scan-detection
  "Create a content-scan detection config.

   Arguments:
   - pattern - Regex pattern
   - opts - Optional {:context-lines n}

   Example:
     (content-scan-detection \"TODO\")
     (content-scan-detection \"FIXME\" {:context-lines 5})"
  core/content-scan-detection)

(def diff-analysis-detection
  "Create a diff-analysis detection config.

   Arguments:
   - pattern - Regex pattern for diff content
   - opts - Optional {:context-lines n}

   Example:
     (diff-analysis-detection \"^-\\\\s*import\\\\s*\\\\{\")"
  core/diff-analysis-detection)

(def plan-output-detection
  "Create a plan-output detection config.

   Arguments:
   - patterns - Vector of regex patterns

   Example:
     (plan-output-detection [\"-/\\\\+.*aws_route\"])"
  core/plan-output-detection)

;; Enforcement config builders
(def warn-enforcement
  "Create a warn-only enforcement config.

   Arguments:
   - message - Violation message

   Example:
     (warn-enforcement \"Found TODO comment\")"
  core/warn-enforcement)

(def halt-enforcement
  "Create a hard-halt enforcement config.

   Arguments:
   - message - Violation message
   - opts - Optional {:remediation string}

   Example:
     (halt-enforcement \"Critical violation\"
                       {:remediation \"Revert the change\"})"
  core/halt-enforcement)

(def approval-enforcement
  "Create a require-approval enforcement config.

   Arguments:
   - message - Violation message
   - approvers - Vector of approver types [:human :senior-engineer :security]

   Example:
     (approval-enforcement \"Network change detected\"
                           [:human :senior-engineer])"
  core/approval-enforcement)

;------------------------------------------------------------------------------ Layer 4
;; Rule resolution

(def resolve-rules
  "Resolve rules from multiple packs.

   Resolution:
   - Group by ID
   - Merge with severity/enforcement escalation

   Arguments:
   - rules - Sequence of rules

   Returns:
   - Vector of resolved rules"
  core/resolve-rules)

(def merge-rules
  "Merge two rules with the same ID.

   Resolution:
   - Override with escalation for severity and enforcement

   Arguments:
   - base - Original rule
   - override - Overriding rule

   Returns:
   - Merged rule"
  core/merge-rules)

;------------------------------------------------------------------------------ Layer 4
;; Utility functions

(def glob-matches?
  "Check if a glob pattern matches a path.

   Arguments:
   - pattern - Glob pattern (supports * and **)
   - path - File path to match

   Returns:
   - boolean

   Example:
     (glob-matches? \"**/*.tf\" \"modules/vpc/main.tf\") ;; => true"
  registry/glob-matches?)

(def compare-versions
  "Compare two DateVer version strings.

   Returns negative if a < b, 0 if equal, positive if a > b.

   Example:
     (compare-versions \"2026.01.22\" \"2026.01.15\") ;; => 7"
  registry/compare-versions)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a registry and add packs
  (def reg (create-registry))

  ;; Create a simple pack
  (def my-pack
    (create-pack "my-rules" "My Rules" "Custom rules" "me"
                 :rules
                 [(create-rule :no-todos
                               "No TODOs"
                               "Forbid TODO comments"
                               :minor "800"
                               (content-scan-detection "TODO")
                               (warn-enforcement "Found TODO"))
                  (create-rule :no-fixmes
                               "No FIXMEs"
                               "Forbid FIXME comments"
                               :minor "800"
                               (content-scan-detection "FIXME")
                               (warn-enforcement "Found FIXME"))]))

  ;; Register it
  (register-pack reg my-pack)

  ;; Check an artifact
  (check-artifact my-pack
                  {:artifact/content "# TODO: implement this\ndef foo(): pass"
                   :artifact/path "main.py"}
                  {:phase :implement})

  ;; Load packs from disk
  (load-pack "terraform-safety.pack.edn")
  (load-all-packs ".miniforge/packs")

  ;; Validate a pack
  (validate-pack my-pack)

  :leave-this-here)
