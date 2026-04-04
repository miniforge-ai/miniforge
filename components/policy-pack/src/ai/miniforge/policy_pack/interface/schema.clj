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

(ns ai.miniforge.policy-pack.interface.schema
  "Schema and validation helpers for the policy-pack public API."
  (:require
   [ai.miniforge.policy-pack.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Schema and validation re-exports

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
