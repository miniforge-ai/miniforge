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
   [ai.miniforge.policy-pack.interface.builders :as builders]
   [ai.miniforge.policy-pack.interface.checking :as checking]
   [ai.miniforge.policy-pack.interface.loading :as loading]
   [ai.miniforge.policy-pack.interface.registry :as registry]
   [ai.miniforge.policy-pack.interface.schema :as schema]
   [ai.miniforge.policy-pack.interface.intent :as intent]
   [ai.miniforge.policy-pack.interface.mapping :as mapping]
   [ai.miniforge.policy-pack.interface.taxonomy :as taxonomy]
   [ai.miniforge.policy-pack.mdc-compiler :as mdc-compiler]))

;------------------------------------------------------------------------------ Schema and registry API

(def Rule schema/Rule)
(def PackManifest schema/PackManifest)
(def RuleSeverity schema/RuleSeverity)
(def RuleEnforcement schema/RuleEnforcement)
(def RuleApplicability schema/RuleApplicability)
(def RuleDetection schema/RuleDetection)
(def rule-severities schema/rule-severities)
(def enforcement-actions schema/enforcement-actions)
(def detection-types schema/detection-types)
(def valid-rule? schema/valid-rule?)
(def validate-rule schema/validate-rule)
(def valid-pack? schema/valid-pack?)
(def validate-pack schema/validate-pack)

(def PolicyPackRegistry registry/PolicyPackRegistry)
(def create-registry registry/create-registry)
(def register-pack registry/register-pack)
(def get-pack registry/get-pack)
(def get-pack-version registry/get-pack-version)
(def list-packs registry/list-packs)
(def delete-pack registry/delete-pack)
(def resolve-pack registry/resolve-pack)
(def get-rules-for-context registry/get-rules-for-context)
(def glob-matches? registry/glob-matches?)
(def compare-versions registry/compare-versions)

;------------------------------------------------------------------------------ Loading and checking API

(def load-pack loading/load-pack)
(def load-pack-from-file loading/load-pack-from-file)
(def load-pack-from-directory loading/load-pack-from-directory)
(def discover-packs loading/discover-packs)
(def load-all-packs loading/load-all-packs)
(def write-pack-to-file loading/write-pack-to-file)

(def detect-violation checking/detect-violation)
(def check-rules checking/check-rules)
(def check-artifact checking/check-artifact)
(def check-artifacts checking/check-artifacts)
(def blocking-violations checking/blocking-violations)
(def approval-required-violations checking/approval-required-violations)
(def warning-violations checking/warning-violations)
(def violation->error checking/violation->error)
(def violation->warning checking/violation->warning)

;------------------------------------------------------------------------------ Builders and rule resolution

(def create-pack builders/create-pack)
(def create-rule builders/create-rule)
(def add-rule-to-pack builders/add-rule-to-pack)
(def remove-rule-from-pack builders/remove-rule-from-pack)
(def update-pack-categories builders/update-pack-categories)
(def content-scan-detection builders/content-scan-detection)
(def diff-analysis-detection builders/diff-analysis-detection)
(def plan-output-detection builders/plan-output-detection)
(def warn-enforcement builders/warn-enforcement)
(def halt-enforcement builders/halt-enforcement)
(def approval-enforcement builders/approval-enforcement)
(def resolve-rules builders/resolve-rules)
(def merge-rules builders/merge-rules)

;------------------------------------------------------------------------------ Intent validation API

(def infer-intent intent/infer-intent)
(def intent-matches? intent/intent-matches?)
(def semantic-intent-check intent/semantic-intent-check)
(def parse-terraform-plan-counts intent/parse-terraform-plan-counts)
(def parse-k8s-diff-counts intent/parse-k8s-diff-counts)

;------------------------------------------------------------------------------ Mapping API

(def MappingArtifact mapping/MappingArtifact)
(def MappingEntry mapping/MappingEntry)
(def valid-mapping? mapping/valid-mapping?)
(def validate-mapping mapping/validate-mapping)
(def load-mapping mapping/load-mapping)
(def resolve-mapping mapping/resolve-mapping)
(def project-report mapping/project-report)

;------------------------------------------------------------------------------ MDC compilation

(def compile-standards-pack
  "Compile all MDC files under a standards directory into a PackManifest.
   Delegates to mdc-compiler/compile-standards-pack."
  mdc-compiler/compile-standards-pack)

(def export-canonical-taxonomy
  "Export the compiler's dewey-ranges as a first-class Taxonomy artifact.
   Delegates to mdc-compiler/export-canonical-taxonomy."
  mdc-compiler/export-canonical-taxonomy)
