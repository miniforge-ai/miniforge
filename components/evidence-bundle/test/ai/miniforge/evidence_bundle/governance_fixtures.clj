;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.governance-fixtures
  "Complete portable records shared by schema and public-boundary regressions."
  (:require [ai.miniforge.content-hash.interface :as hash]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} knowledge []
  {:knowledge/id (random-uuid)
   :knowledge/type :policy-pack
   :knowledge/trust-level :trusted
   :knowledge/authority :authority/instruction
   :knowledge/source "policy/example"
   :knowledge/content-hash (hash/content-hash {:rule :example})})

(defn- ^{:stratum 0} violation []
  {:violation/id (random-uuid)
   :violation/rule-id :rule/example
   :violation/pack-id :pack/example
   :violation/gate-id :review
   :violation/severity :medium
   :violation/message "Example finding"
   :violation/auto-fixable? false
   :violation/remediation "Review finding"})

(defn- ^{:stratum 0} waiver [evaluation-id]
  {:waiver/id (random-uuid)
   :waiver/evaluation-id evaluation-id
   :waiver/violations [:rule/example]
   :waiver/actor "operator"
   :waiver/reason "Accepted for this evaluation"
   :waiver/timestamp (java.util.Date.)})

(defn- ^{:stratum 0} resolved-rule []
  {:rule/id :rule/example
   :pack/id :pack/example
   :rule/severity :medium
   :rule/enabled? true
   :rule/selected? true
   :rule/proposals [{:pack/id :pack/example
                     :rule/severity :medium
                     :rule/enabled? true}]})

(defn- ^{:stratum 0} resolved-pack []
  {:pack/id :pack/example
   :pack/version "2.1.3"
   :pack/content-hash (hash/content-hash {})})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} gate []
  (let [evaluation-id (random-uuid)
        packs [(resolved-pack)]
        rules [(resolved-rule)]
        violations [(violation)]
        waivers [(waiver evaluation-id)]]
    {:gate-execution/gate-id :review
     :gate-execution/phase :review
     :gate-execution/outcome :waived
     :gate-execution/evaluation-id evaluation-id
     :gate-execution/allow-override? true
     :gate-execution/binding {:gate/id :review
                              :binding/packs [{:pack/id :pack/example
                                               :pack/version "^2.0.0"}]}
     :gate-execution/packs packs
     :gate-execution/resolved-rules [:rule/example]
     :gate-execution/resolution-trace rules
     :gate-execution/violations violations
     :gate-execution/waivers waivers}))
