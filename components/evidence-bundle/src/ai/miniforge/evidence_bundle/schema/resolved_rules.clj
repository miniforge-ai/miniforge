;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.resolved-rules
  "Portable rule-resolution decisions, including disabled and filtered rules."
  (:require [ai.miniforge.evidence-bundle.schema.governance-values :as values]
            [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} severity (into [:enum] shared/severities))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} proposal
  [:map [:pack/id keyword?] [:rule/severity severity] [:rule/enabled? boolean?]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} record-schema
  [:map [:rule/id keyword?] [:pack/id keyword?]
   [:rule/severity severity] [:rule/enabled? boolean?] [:rule/selected? boolean?]
   [:rule/proposals (values/record-vector proposal)]])
