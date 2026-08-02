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
(ns ai.miniforge.policy-pack.interface
  "Canonical public boundary for the policy-pack component.
   Public API groups live under ai.miniforge.policy-pack.interface.* namespaces,
   while this namespace remains the Polylith entrypoint."
  (:require
   [ai.miniforge.policy-pack.capability :as capability]
   [ai.miniforge.policy-pack.interface.builders :as builders]
   [ai.miniforge.policy-pack.interface.checking :as checking]
   [ai.miniforge.policy-pack.interface.loading :as loading]
   [ai.miniforge.policy-pack.interface.registry :as registry]
   [ai.miniforge.policy-pack.interface.schema :as schema]
   [ai.miniforge.policy-pack.interface.intent :as intent]
   [ai.miniforge.policy-pack.interface.mapping :as mapping]
   [ai.miniforge.policy-pack.interface.prompt-template :as prompt-template]
   [ai.miniforge.policy-pack.detection :as detection]
   ;; Loaded for its side effect: registers the built-in :custom detectors so
   ;; the fail-closed resolver (compiler/resolve-detector) sees them whenever a
   ;; pack is compiled through this boundary.
   [ai.miniforge.policy-pack.builtin-detectors]
   [ai.miniforge.policy-pack.mdc-compiler :as mdc-compiler]
   [ai.miniforge.policy-pack.software-factory :as software-factory]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Schema and registry API
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
  schema/RuleSeverity)

(def ^{:stratum 0} RuleEnforcement
  "Malli enum schema (`[:enum :hard-halt :require-approval :warn :audit]`) for
   a rule's enforcement action."
  schema/RuleEnforcement)

(def ^{:stratum 0} RuleApplicability
  "Malli schema (a `[:map ...]` vector) for when a rule applies — optional
   task-types, file-globs, resource-patterns, repo-types, and phases."
  schema/RuleApplicability)

(def ^{:stratum 0} RuleDetection
  "Malli schema (a `[:map ...]` vector) for how to detect violations —
   required :type plus optional pattern(s), context-lines, custom-fn,
   capability, mode, and email-pattern."
  schema/RuleDetection)

(def ^{:stratum 0} rule-severities
  "Vector of severity keywords `[:critical :high :medium :low :info]`, ordered most
   to least severe."
  schema/rule-severities)

(def ^{:stratum 0} enforcement-actions
  "Vector of enforcement keywords `[:hard-halt :require-approval :warn :audit]`,
   ordered strictest to most lenient."
  schema/enforcement-actions)

(def ^{:stratum 0} detection-types
  "Vector of detection-type keywords
   `[:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis
   :custom :capability]`."
  schema/detection-types)

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

(def ^{:stratum 0} PolicyPackRegistry
  "Protocol for managing policy packs: CRUD, import/export, validation, and
   composition. Implemented by the in-memory registry from create-registry."
  registry/PolicyPackRegistry)

(def ^{:stratum 0} create-registry
  "Create an in-memory PolicyPackRegistry. Arity [] or [opts] (opts may carry
   :logger). Returns a registry value backed by an atom."
  registry/create-registry)

(def ^{:stratum 0} register-pack
  "Protocol fn. (register-pack registry pack) registers a pack (or new
   version), keyed by :pack/id and :pack/version. Returns the registered pack;
   returns an :invalid-input anomaly map when the pack fails schema validation."
  registry/register-pack)

(def ^{:stratum 0} get-pack
  "Protocol fn. (get-pack registry pack-id) returns the latest-version pack for
   pack-id, or nil if none is registered."
  registry/get-pack)

(def ^{:stratum 0} get-pack-version
  "Protocol fn. (get-pack-version registry pack-id version) returns the pack at
   the exact version, or nil if not found."
  registry/get-pack-version)

(def ^{:stratum 0} list-packs
  "Protocol fn. (list-packs registry criteria) returns a vector of pack-summary
   maps (:pack/id :pack/name :pack/version :pack/author :pack/description
   :rule-count). Criteria may filter by :category, :author, :search."
  registry/list-packs)

(def ^{:stratum 0} delete-pack
  "Protocol fn. (delete-pack registry pack-id version) removes the pack version.
   Returns true if deleted, false if it was not present."
  registry/delete-pack)

(def ^{:stratum 0} resolve-pack
  "Protocol fn. (resolve-pack registry pack-id) returns the pack with all
   :pack/extends parents recursively merged (child rules override parents by
   :rule/id), or nil if the pack is not found."
  registry/resolve-pack)

(def ^{:stratum 0} get-rules-for-context
  "Protocol fn. (get-rules-for-context registry pack-ids context) resolves the
   named packs, filters their rules by the context (:task, :artifact, :repo,
   :phase), deduplicates by :rule/id, and returns a vector of applicable rules."
  registry/get-rules-for-context)

(def ^{:stratum 0} glob-matches?
  "Predicate. (glob-matches? pattern path) returns true when path matches the
   glob pattern (supports * within a segment and ** across segments); false on
   no match or a bad pattern."
  registry/glob-matches?)

(def ^{:stratum 0} compare-versions
  "Compare two DateVer (YYYY.MM.DD) version strings. Returns a negative int if
   a < b, 0 if equal, positive if a > b; unparseable versions compare as
   [0 0 0]."
  registry/compare-versions)

;------------------------------------------------------------------------------ Loading and checking API
(def ^{:stratum 0} load-pack
  "Load a policy pack from a path, auto-detecting single-EDN-file vs directory
   layout. Returns {:success? bool :pack PackManifest :errors [...]}."
  loading/load-pack)

(def ^{:stratum 0} load-pack-from-file
  "Load a policy pack from a single EDN manifest file (pack.edn or
   *.pack.edn). Returns {:success? bool :pack PackManifest :errors [...]}."
  loading/load-pack-from-file)

(def ^{:stratum 0} load-pack-from-directory
  "Load a policy pack from a directory (pack.edn manifest plus optional rules/
   subtree; directory rules override inline rules by :rule/id). Returns
   {:success? bool :pack PackManifest :errors [...]}."
  loading/load-pack-from-directory)

(def ^{:stratum 0} resolve-overlay
  "Resolve an overlay pack by merging inherited rules from its :pack/extends
   base packs (looked up in pack-store), appending overlay rules, applying
   :pack/overrides, and inheriting the taxonomy ref. Returns
   {:success? true :pack <resolved PackManifest>} or
   {:success? false :errors [...]} on missing base, taxonomy conflict, or
   rule-id collision."
  loading/resolve-overlay)

(def ^{:stratum 0} discover-packs
  "Discover packs in a directory: top-level *.pack.edn files and subdirectories
   containing pack.edn. Returns a vector of {:path string :type :file|:directory}
   (nil when the directory does not exist)."
  loading/discover-packs)

(def ^{:stratum 0} load-all-packs
  "Load every pack discovered in a directory. Arity [packs-dir] or
   [packs-dir opts] (opts: :validate-dependencies? default true,
   :max-dependency-depth default 5). Returns {:loaded [PackManifest...]
   :failed [{:path :errors}...] :dependency-validation {...}?}."
  loading/load-all-packs)

(def ^{:stratum 0} write-pack-to-file
  "Serialize a PackManifest to an EDN file via pprint, with its timestamps
   normalized to ISO-8601 strings so the file reads back; a timestamp that is
   neither an Instant nor a Date is refused and nothing is written.
   (write-pack-to-file pack file-path) returns {:success? bool :error
   string-or-nil} — nil on success."
  loading/write-pack-to-file)

(def ^{:stratum 0} detect-violation
  "Detect a violation for one rule against an artifact, dispatching on
   :rule/detection :type. (detect-violation rule artifact context) returns a
   violation map (keys include :type :rule-id :matches :message) or nil when
   clean."
  checking/detect-violation)

(def ^{:stratum 0} detect-semantic
  "Run the LLM-as-judge detector for a heuristic :custom rule.
   (detect-semantic rule artifact context) returns a :type :semantic violation
   map when the judge reports violations, a :type :semantic-error map when the
   judge throws, or nil when clean or when the semantic wiring is absent from
   context."
  checking/detect-semantic)

(def ^{:stratum 0} register-custom-fn!
  "Register a custom policy detector implementation for a `:custom-fn` symbol.
   Explicit registration replaces ambient var resolution for custom detector
   extension points."
  detection/register-custom-fn!)

(def ^{:stratum 0} unregister-custom-fn!
  "Remove a custom policy detector registration. Intended for tests and reload
   hygiene."
  detection/unregister-custom-fn!)

(def ^{:stratum 0} check-rules
  "Check multiple rules against one artifact.
   (check-rules rules artifact context) returns a vector of
   {:rule rule :violation violation-map :timestamp Instant} for each rule that
   fired."
  checking/check-rules)

(def ^{:stratum 0} check-artifact
  "Check one artifact against all applicable rules from a pack (or vector of
   packs). (check-artifact pack artifact context) returns
   {:passed? bool :violations [...] :blocking [...] :require-approval [...]
   :warnings [...] :audits [...] :read-only? bool} where :passed? is true when
   there are no hard-halt blocking violations."
  checking/check-artifact)

(def ^{:stratum 0} check-artifacts
  "Check several artifacts against pack rules.
   (check-artifacts pack artifacts context) returns a vector of check-artifact
   result maps, one per artifact."
  checking/check-artifacts)

(def ^{:stratum 0} classify-violations
  "Exhaustive violation classification: {:blocking :require-approval
   :warnings :audits :unknown}; no default-pass branch."
  checking/classify-violations)

(def ^{:stratum 0} violation->error
  "Convert a check-rules violation entry into a gate error map
   {:code :message :severity :location {:matches :path} :remediation}."
  checking/violation->error)

(def ^{:stratum 0} violation->warning
  "Convert a check-rules violation entry into a gate warning map
   {:code :message :severity}."
  checking/violation->warning)

(def ^{:stratum 0} resolve-detector
  "Return the detection-mechanism keyword a rule binds to: one of
   :content-scan/:diff-analysis/:plan-output/:state-comparison/:ast-analysis,
   or :capability/:custom/:semantic, or :none when it binds to no available
   mechanism. (resolve-detector rule)"
  checking/resolve-detector)

(def ^{:stratum 0} rule-enabled?
  "Predicate. (rule-enabled? rule) returns true unless the rule explicitly sets
   :rule/enabled? to false; a missing flag means enabled."
  checking/rule-enabled?)

(def ^{:stratum 0} compile-rule-check
  "Compile one enabled rule into an executable N4 check entry.
   (compile-rule-check rule) returns {:rule :detector :check-fn}, or an
   :invalid-input anomaly when the rule cannot bind to a detector."
  checking/compile-rule-check)

(def ^{:stratum 0} compile-pack-checks
  "Compile every enabled rule in a pack into executable N4 check entries.
   (compile-pack-checks pack) returns {:ok true :rule-count :detector-counts
   :compiled-rules [...]}, or an :invalid-input anomaly naming unbindable
   rule ids."
  checking/compile-pack-checks)

(def ^{:stratum 0} compile-pack
  "Validate that every enabled rule in a pack binds to a detection mechanism.
   (compile-pack pack) returns {:ok true :rule-count int :detector-counts {...}}
   on success, or an :invalid-input anomaly map (data, not a throw) naming
   :unbindable-rule-ids when any enabled rule resolves to :none."
  checking/compile-pack)

;------------------------------------------------------------------------------ Capability registry API
(def ^{:stratum 0} register-capability!
  "Register (or replace) a mechanical-check capability in the registry.
   (register-capability! k entry), where k is a keyword and entry is
   {:meta map :check (fn [artifact context] -> violation-or-nil)}, returns the
   stored entry on success, or an :invalid-input anomaly map (data, not a throw)
   on bad input."
  capability/register-capability!)

(def ^{:stratum 0} get-capability
  "Return the registered capability entry for keyword k, or nil when
   unregistered."
  capability/get-capability)

(def ^{:stratum 0} list-capabilities
  "Return the set of registered capability keywords."
  capability/list-capabilities)

(def ^{:stratum 0} capability-available?
  "Predicate. Returns true when capability keyword k is registered, false
   otherwise."
  capability/capability-available?)

;------------------------------------------------------------------------------ Builders and rule resolution
(def ^{:stratum 0} create-pack
  "Build a new PackManifest map with defaults.
   (create-pack id name description author & {:keys [version license rules]}).
   :version defaults to today's DateVer, :rules to []. Returns the pack map."
  builders/create-pack)

(def ^{:stratum 0} create-rule
  "Build a new rule map from required fields plus options.
   (create-rule id title description severity category detection enforcement
   & {:keys [applies-to agent-behavior examples]}). Returns the rule map."
  builders/create-rule)

(def ^{:stratum 0} add-rule-to-pack
  "Append a rule to a pack and bump :pack/updated-at. (add-rule-to-pack pack
   rule) returns the updated pack map."
  builders/add-rule-to-pack)

(def ^{:stratum 0} remove-rule-from-pack
  "Remove a rule by :rule/id from a pack and bump :pack/updated-at.
   (remove-rule-from-pack pack rule-id) returns the updated pack map."
  builders/remove-rule-from-pack)

(def ^{:stratum 0} update-pack-categories
  "Regenerate :pack/categories from the pack's rules, grouped by
   :rule/category. (update-pack-categories pack) returns the updated pack map."
  builders/update-pack-categories)

(def ^{:stratum 0} content-scan-detection
  "Build a content-scan detection config map. (content-scan-detection pattern)
   or (content-scan-detection pattern {:context-lines n}) (default 2). Returns
   {:type :content-scan :pattern ... :context-lines ...}."
  builders/content-scan-detection)

(def ^{:stratum 0} diff-analysis-detection
  "Build a diff-analysis detection config map. (diff-analysis-detection pattern)
   or (diff-analysis-detection pattern {:context-lines n}) (default 3). Returns
   {:type :diff-analysis :pattern ... :context-lines ...}."
  builders/diff-analysis-detection)

(def ^{:stratum 0} plan-output-detection
  "Build a plan-output detection config map. (plan-output-detection patterns)
   returns {:type :plan-output :patterns patterns}."
  builders/plan-output-detection)

(def ^{:stratum 0} warn-enforcement
  "Build a warn-only enforcement config map. (warn-enforcement message) returns
   {:action :warn :message message}."
  builders/warn-enforcement)

(def ^{:stratum 0} halt-enforcement
  "Build a hard-halt enforcement config map. (halt-enforcement message) or
   (halt-enforcement message {:remediation s}) returns
   {:action :hard-halt :message ... :remediation} (:remediation present only when supplied)."
  builders/halt-enforcement)

(def ^{:stratum 0} approval-enforcement
  "Build a require-approval enforcement config map. (approval-enforcement
   message approvers) returns {:action :require-approval :message ...
   :approvers ...}."
  builders/approval-enforcement)

(def ^{:stratum 0} resolve-rules
  "Resolve a sequence of rules from multiple packs: group by :rule/id and merge
   each group (severity escalates higher, enforcement escalates stricter).
   (resolve-rules rules) returns a vector of resolved rules."
  builders/resolve-rules)

(def ^{:stratum 0} merge-rules
  "Merge two same-id rules. (merge-rules base override) returns a rule map where
   override wins for most fields but :rule/severity keeps the more severe and
   :rule/enforcement :action keeps the stricter."
  builders/merge-rules)

(def ^{:stratum 0} filter-applicable-rules
  "Filter rules to those enabled and applicable to a context (by file globs,
   task type, and phase). (filter-applicable-rules rules context) returns a
   vector of applicable rules."
  checking/filter-applicable-rules)

(def ^{:stratum 0} rule-applies-to-phase?
  "Predicate. (rule-applies-to-phase? rule phase) returns true when the rule
   has no :phases constraint or its constraint contains phase."
  checking/rule-applies-to-phase?)

;------------------------------------------------------------------------------ Intent validation API
(def ^{:stratum 0} infer-intent
  "Infer the intent keyword from resource change counts.
   (infer-intent {:creates :updates :destroys}) returns one of
   :refactor/:create/:update/:destroy/:migrate, or :mixed when no clear pattern."
  intent/infer-intent)

(def ^{:stratum 0} intent-matches?
  "Validate that a declared intent matches actual resource change counts.
   (intent-matches? declared counts) returns {:passed? true} when consistent,
   else {:passed? false :violations [...]}. Unknown intents pass by default."
  intent/intent-matches?)

(def ^{:stratum 0} semantic-intent-check
  "Full semantic intent check (N4 §4).
   (semantic-intent-check declared-intent counts) returns
   {:passed? bool :violations [...] :inferred-intent keyword :metadata {...}}."
  intent/semantic-intent-check)

(def ^{:stratum 0} parse-terraform-plan-counts
  "Parse terraform plan output into resource change counts.
   (parse-terraform-plan-counts plan-output) returns
   {:creates int :updates int :destroys int}."
  intent/parse-terraform-plan-counts)

(def ^{:stratum 0} parse-k8s-diff-counts
  "Parse kubectl diff output into resource change counts.
   (parse-k8s-diff-counts diff-output) returns
   {:creates int :updates int :destroys int}."
  intent/parse-k8s-diff-counts)

;------------------------------------------------------------------------------ Mapping API
(def ^{:stratum 0} MappingArtifact
  "Malli schema (a `[:map ...]` vector) for a mapping artifact — an independent,
   versioned bridge between a source and target policy system, carrying entries
   and optional authorship."
  mapping/MappingArtifact)

(def ^{:stratum 0} MappingEntry
  "Malli schema (a `[:map ...]` vector) for one mapping entry — an optional
   source rule/category, an optional target control, a :mapping/type, and
   optional notes."
  mapping/MappingEntry)

(def ^{:stratum 0} valid-mapping?
  "Predicate. Returns true when the value conforms to the MappingArtifact
   schema, false otherwise."
  mapping/valid-mapping?)

(def ^{:stratum 0} validate-mapping
  "Validate a mapping artifact against the MappingArtifact schema. Returns
   {:valid? bool :errors map-or-nil}."
  mapping/validate-mapping)

(def ^{:stratum 0} load-mapping
  "Load a mapping artifact from an EDN file. (load-mapping path) returns
   {:success? true :mapping <MappingArtifact>} or
   {:success? false :error <message>} on read or validation failure."
  mapping/load-mapping)

(def ^{:stratum 0} resolve-mapping
  "Resolve a mapping against a pack, enriching each entry with match status.
   (resolve-mapping mapping pack) returns a vector of entry maps each carrying
   :matched? (and :rule-title when a source rule matched)."
  mapping/resolve-mapping)

(def ^{:stratum 0} project-report
  "Project a mapping onto a pack to produce a coverage report.
   (project-report mapping pack) returns
   {:target-controls [{:control :type :matched? :source :notes} ...]
   :summary {:exact :broad :partial :none :unmatched}}."
  mapping/project-report)

;------------------------------------------------------------------------------ Prompt templates
(def ^{:stratum 0} interpolate
  "Replace {{variable}} placeholders in a template with values from a bindings
   map (keys may be strings or keywords; unknown variables become \"\").
   (interpolate template bindings) returns the interpolated string."
  prompt-template/interpolate)

(def ^{:stratum 0} render-repair-prompt
  "Render a complete repair prompt for a violation.
   (render-repair-prompt violation rule pack) resolves the repair template
   (rule → pack → default), interpolates it, and returns
   {:role :user :content <string>}."
  prompt-template/render-repair-prompt)

(def ^{:stratum 0} render-behavior-section
  "Render the numbered behavior-rules section for prompt injection.
   (render-behavior-section behaviors pack) returns the formatted string, or
   nil when behaviors is empty."
  prompt-template/render-behavior-section)

(def ^{:stratum 0} render-knowledge-section
  "Render the reference-material section for prompt injection.
   (render-knowledge-section knowledge pack), where knowledge is a seq of
   {:rule/title :content} maps, returns the formatted string, or nil when
   empty."
  prompt-template/render-knowledge-section)

;------------------------------------------------------------------------------ MDC compilation
(def ^{:stratum 0} compile-standards-pack
  "Compile all MDC files under a standards directory into a PackManifest.
   Delegates to mdc-compiler/compile-standards-pack."
  mdc-compiler/compile-standards-pack)

(def ^{:stratum 0} export-canonical-taxonomy
  "Export the compiler's dewey-ranges as a first-class Taxonomy artifact.
   Delegates to mdc-compiler/export-canonical-taxonomy."
  mdc-compiler/export-canonical-taxonomy)

;------------------------------------------------------------------------------ Software Factory
(def ^{:stratum 0} evaluate-external-pr
  "Evaluate an external PR diff against policy packs in read-only mode."
  software-factory/evaluate-external-pr)

(def ^{:stratum 0} parse-pr-diff
  "Parse a unified diff into artifact-like inputs for external evaluation."
  software-factory/parse-pr-diff)
