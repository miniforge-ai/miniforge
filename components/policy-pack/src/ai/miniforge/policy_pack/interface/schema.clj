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
