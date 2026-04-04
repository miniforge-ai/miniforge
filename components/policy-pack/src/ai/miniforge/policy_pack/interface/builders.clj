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

(def create-pack core/create-pack)
(def create-rule core/create-rule)
(def add-rule-to-pack core/add-rule-to-pack)
(def remove-rule-from-pack core/remove-rule-from-pack)
(def update-pack-categories core/update-pack-categories)
(def content-scan-detection core/content-scan-detection)
(def diff-analysis-detection core/diff-analysis-detection)
(def plan-output-detection core/plan-output-detection)
(def warn-enforcement core/warn-enforcement)
(def halt-enforcement core/halt-enforcement)
(def approval-enforcement core/approval-enforcement)
(def resolve-rules core/resolve-rules)
(def merge-rules core/merge-rules)
(def evaluate-external-pr external/evaluate-external-pr)
(def parse-pr-diff external/parse-pr-diff)
