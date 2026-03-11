(ns ai.miniforge.policy-pack.software-factory
  "Software-factory specific policy helpers built on the generic policy-pack SDK."
  (:require
   [ai.miniforge.policy-pack.external :as external]))

(def evaluate-external-pr
  "Evaluate an external PR diff against policy packs in read-only mode."
  external/evaluate-external-pr)

(def parse-pr-diff
  "Parse a unified diff into artifact-like inputs for external evaluation."
  external/parse-pr-diff)
