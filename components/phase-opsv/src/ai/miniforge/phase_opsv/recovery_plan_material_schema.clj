;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-material-schema
  (:require [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as plan]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} kind :recovery-plan-admission)

(def ^{:stratum 0} artifact-type :opsv-verification-report)

(def ^{:stratum 0} content-key :opsv/recovery-plan-admission)

(def ^{:stratum 0} admission-fields
  [:recovery/plan :recovery/hash :recovery/plan-decision-envelope
   :recovery/plan-gate-checks :recovery/plan-refusals])

(def ^{:stratum 0} policy-fields [:pins/pack-revision :pins/rule-ids])

(def ^{:stratum 0} Check
  [:map [:gate :keyword] [:passed? :boolean] [:errors {:optional true} [:vector :map]]])

(def ^{:stratum 0} ReadArguments [:tuple retained/Context :uuid])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} material [kind artifact-type content-key])

(def ^{:stratum 1} Checks
  [:map {:closed true} [:passed? :boolean] [:results [:vector {:min 1} Check]]
   [:failed-gates [:vector Check]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Content
  [:map {:closed true}
   [:verification/kind [:= kind]]
   [:verification/passed? :boolean]
   [:verification/criteria [:map-of :keyword :boolean]]
   [:recovery/plan plan/Plan]
   [:recovery/hash opsv/PolicyHash]
   [:recovery/plan-decision-envelope decision/DecisionEnvelope]
   [:recovery/plan-gate-checks Checks]
   [:recovery/plan-refusals [:vector Check]]])

(comment ::registered-verification-report-kind-not-an-execution-grant-or-candidate-slo-result)
