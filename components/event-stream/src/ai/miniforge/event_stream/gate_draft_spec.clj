;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.gate-draft-spec
  "N3 §3.9.1 sequence-free gate evidence contracts."
  (:require [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.event-stream.gate-contract :as contract]
            [ai.miniforge.policy-clause.interface :as clause]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Identity
  [:map
   [:event/version [:= "1.0.0"]]
   [:scope/type [:= :workflow]]
   [:workflow/id :uuid]
   [:gate/phase :keyword]])

(def ^{:stratum 0} ViolationSummary
  [:map {:closed true}
   [:message {:optional true} [:maybe :string]]
   [:artifact-path {:optional true} [:maybe :string]]
   [:match-count nat-int?]])

(def ^{:stratum 0} Decision
  [:and
   [:map
    [:envelope/id :uuid]
    [:gate/decision-envelope decision/DecisionEnvelope]]
   [:fn contract/decision-agrees?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} RuleApplied
  [:and
   [:map
    [:rule/id :keyword]
    [:rule/status [:enum :passed :failed :skipped-by-phase :not-applicable :guidance]]
    [:envelope/id {:optional true} [:maybe :uuid]]
    [:rule/severity {:optional true} [:maybe clause/Severity]]
    [:rule/enforcement {:optional true} [:maybe clause/EnforcementAction]]
    [:rule/violation {:optional true} [:maybe ViolationSummary]]]
   [:fn contract/violation-agrees?]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Draft
  [:and Identity [:fn contract/phase-agrees?]
   [:multi {:dispatch :event/type}
    [:gate/decision Decision]
    [:gate/rule-applied RuleApplied]]])

(comment
  Draft)
