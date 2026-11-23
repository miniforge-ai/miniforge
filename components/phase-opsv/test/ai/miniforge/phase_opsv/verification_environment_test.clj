;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-environment-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.verification-environment :as subject]
            [ai.miniforge.phase-opsv.verification-environment-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} request []
  (select-keys (environment/verification-binding)
               [:verification/id :candidate/hash :experiment-pack/hash]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} pin []
  (subject/pin (fixtures/context (fixtures/declaration (request)))
               (environment/fingerprint) (request)))

(deftest ^{:stratum 1} declaration-cannot-supply-baseline-or-host-identities-test
  (let [declared (fixtures/declaration (request))]
    (doseq [invalid [nil {} []
                    (assoc declared :comparison/baseline (environment/fingerprint))
                    (assoc declared :workflow/id (random-uuid))
                    (assoc declared :verification/id (random-uuid))
                    (assoc declared :comparison/changes {:ignored-fields [:config-hash]})]]
      (is (= :invalid-input
              (:anomaly/type (subject/pin (fixtures/context invalid)
                                         (environment/fingerprint) (request))))))))

(deftest ^{:stratum 1} stale-content-declarations-and-invalid-original-baselines-fail-test
  (let [declared (fixtures/declaration (request))]
    (doseq [key [:candidate/hash :experiment-pack/hash]]
      (is (= :conflict
              (:anomaly/type (subject/pin
                                (fixtures/context (assoc declared key (hash/content-hash :stale)))
                                (environment/fingerprint) (request))))))
    (doseq [baseline [nil {} (assoc (environment/fingerprint) :cluster "foreign")]]
      (is (anomaly/anomaly? (subject/pin (fixtures/context declared) baseline (request)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} host-bound-contract-retains-independent-original-expectations-test
  (let [pinned (pin)
        digest (:comparison/hash pinned)
        contract (:comparison/contract pinned)
        baseline (environment/fingerprint)
        candidate (merge baseline (environment/changes))]
    (is (= (environment/verification-binding) (:comparison/binding contract)))
    (is (= baseline (:comparison/baseline contract)))
    (is (= baseline (subject/compare-observation pinned digest :rollback (fixtures/observation baseline))))
    (is (= candidate (subject/compare-observation pinned digest :candidate (fixtures/observation candidate))))
    (is (anomaly/anomaly? (subject/compare-observation pinned digest :rollback (fixtures/observation candidate))))
    (is (anomaly/anomaly? (subject/compare-observation pinned digest :candidate
                          (assoc-in (fixtures/observation candidate) [:runtime/target :namespace-uid] "replaced"))))
    (let [forged (assoc-in contract [:comparison/baseline :config-hash] "forged")
          repinned {:comparison/contract forged :comparison/hash (hash/content-hash forged)}]
      (is (anomaly/anomaly? (subject/compare-observation repinned digest :candidate
                                                      (fixtures/observation candidate)))))))

(comment ::host-binding-and-independent-digest-are-not-adapter-inputs)
