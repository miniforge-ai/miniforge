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
