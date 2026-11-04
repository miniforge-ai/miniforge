;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-preparation-host-fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.artifact-test-support :as storage]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as candidate]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-preparation-fixtures :as preparation]
            [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} environment [ctx input]
  (let [synthesized (candidate/synthesized ctx)
        candidate-hash (hash/content-hash (:opsv/operational-policy synthesized))
        pack-hash (hash/content-hash (:opsv/experiment-pack synthesized))
        target (get-in input [:declaration :runtime/target])
        expected (preparation/replica-expectation (:projected input) 1 3)]
    {:candidate/hash candidate-hash
     :experiment-pack/hash pack-hash
     :runtime/target target
     :comparison/changes {}
     :comparison/replica-contract {:candidate expected
                                 :rollback expected}}))

(defn- ^{:stratum 0} published-verification [ctx input directory settings calls]
  (let [measurements (atom [])
        ready (preparation/adapter-context ctx input directory measurements settings)
        verified (storage/step ready [:opsv/verify phase/verify])
        completed (storage/step verified [:opsv/actuate phase/actuate])
        output (support/phase-output completed :opsv/actuate)
        retained (get-in @measurements [0 2])]
    {:output output
     :retained retained
     :calls @calls}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} provide-observations [input operation ctx directory _baseline]
  (let [declared (environment ctx input)
        ready (-> (storage/configured ctx)
                  (assoc-in [:execution/opts :opsv/verification-environment] declared)
                  (assoc-in [:execution/phase-results :opsv/synthesize :result :output :opsv/environment-fingerprint]
                            (get-in input [:projected :environment-fingerprint])))]
    (operation (preparation/with-transport (:routes input) (partial published-verification ready input directory)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-context [operation]
  (let [input (preparation/input (preparation/email-routes))]
    (candidate/with-context (partial provide-observations input operation))))

(comment ::actual-native-preparation-through-host-retention-and-finalization)
