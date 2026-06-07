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

(ns ai.miniforge.policy-pack.interface.builders
  "Policy-pack builders, rule resolution, and external-PR evaluation."
  (:require
   [ai.miniforge.policy-pack.core :as core]
   [ai.miniforge.policy-pack.external :as external]))

;------------------------------------------------------------------------------ Layer 0
;; Builders and resolution helpers

(def create-pack
  "Build a new PackManifest map with defaults.
   (create-pack id name description author & {:keys [version license rules]}).
   :version defaults to today's DateVer, :rules to []. Returns the pack map."
  core/create-pack)

(def create-rule
  "Build a new rule map from required fields plus options.
   (create-rule id title description severity category detection enforcement
   & {:keys [applies-to agent-behavior examples]}). Returns the rule map."
  core/create-rule)

(def add-rule-to-pack
  "Append a rule to a pack and bump :pack/updated-at. (add-rule-to-pack pack
   rule) returns the updated pack map."
  core/add-rule-to-pack)

(def remove-rule-from-pack
  "Remove a rule by :rule/id from a pack and bump :pack/updated-at.
   (remove-rule-from-pack pack rule-id) returns the updated pack map."
  core/remove-rule-from-pack)

(def update-pack-categories
  "Regenerate :pack/categories from the pack's rules, grouped by
   :rule/category. (update-pack-categories pack) returns the updated pack map."
  core/update-pack-categories)

(def content-scan-detection
  "Build a content-scan detection config map. (content-scan-detection pattern)
   or (content-scan-detection pattern {:context-lines n}) (default 2). Returns
   {:type :content-scan :pattern ... :context-lines ...}."
  core/content-scan-detection)

(def diff-analysis-detection
  "Build a diff-analysis detection config map. (diff-analysis-detection pattern)
   or (diff-analysis-detection pattern {:context-lines n}) (default 3). Returns
   {:type :diff-analysis :pattern ... :context-lines ...}."
  core/diff-analysis-detection)

(def plan-output-detection
  "Build a plan-output detection config map. (plan-output-detection patterns)
   returns {:type :plan-output :patterns patterns}."
  core/plan-output-detection)

(def warn-enforcement
  "Build a warn-only enforcement config map. (warn-enforcement message) returns
   {:action :warn :message message}."
  core/warn-enforcement)

(def halt-enforcement
  "Build a hard-halt enforcement config map. (halt-enforcement message) or
   (halt-enforcement message {:remediation s}) returns
   {:action :hard-halt :message ... :remediation} (:remediation present only when supplied)."
  core/halt-enforcement)

(def approval-enforcement
  "Build a require-approval enforcement config map. (approval-enforcement
   message approvers) returns {:action :require-approval :message ...
   :approvers ...}."
  core/approval-enforcement)

(def resolve-rules
  "Resolve a sequence of rules from multiple packs: group by :rule/id and merge
   each group (severity escalates higher, enforcement escalates stricter).
   (resolve-rules rules) returns a vector of resolved rules."
  core/resolve-rules)

(def merge-rules
  "Merge two same-id rules. (merge-rules base override) returns a rule map where
   override wins for most fields but :rule/severity keeps the more severe and
   :rule/enforcement :action keeps the stricter."
  core/merge-rules)

(def evaluate-external-pr
  "Evaluate an external PR against policy packs in read-only mode.
   (evaluate-external-pr packs pr-data) returns
   {:evaluation/passed? bool :evaluation/violations [...]
   :evaluation/summary {:critical :major :minor :info :total}
   :evaluation/artifacts-checked int :evaluation/packs-applied [string]}."
  external/evaluate-external-pr)

(def parse-pr-diff
  "Parse a unified diff string into artifact maps. (parse-pr-diff diff-content)
   returns a vector of {:artifact/path :artifact/content (added lines)
   :artifact/diff (raw hunk)}, or nil for blank input."
  external/parse-pr-diff)
