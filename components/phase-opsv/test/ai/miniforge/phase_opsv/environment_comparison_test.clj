;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.interface :as phase]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} pin-retains-original-baseline-and-is-deterministic-test
  (let [plan (fixtures/plan)
        pinned (phase/pin-verification-environment plan)
        unchanged (phase/pin-verification-environment (assoc plan :comparison/changes {}))]
    (is (not (anomaly/anomaly? pinned)))
    (is (m/validate phase/VerificationEnvironmentPlan plan))
    (is (= plan (:comparison/contract pinned)))
    (is (= pinned (phase/pin-verification-environment plan)))
    (is (not= (:comparison/hash pinned) (:comparison/hash unchanged)))))

(deftest ^{:stratum 0} invalid-environment-plans-fail-closed-test
  (let [plan (fixtures/plan)]
    (doseq [invalid [nil {}
                     (assoc plan :unexpected true)
                     (assoc plan :comparison/baseline {})
                     (assoc plan :comparison/changes {:cluster "foreign"})
                     (assoc plan :comparison/changes {:node-pools ["foreign"]})
                     (assoc plan :comparison/changes {:config-hash nil})
                     (assoc-in plan [:comparison/binding :candidate/hash] "stale")
                     (assoc-in plan [:comparison/binding :runtime/target :cluster] "foreign")
                     (assoc-in plan [:comparison/baseline :image-digests] {})]]
      (is (= :invalid-input
             (:anomaly/type (phase/pin-verification-environment invalid)))))))

(deftest ^{:stratum 0} undeclared-image-values-remain-baseline-invariants-test
  (let [plan (assoc (fixtures/plan) :comparison/changes {:config-hash "candidate-config"})
        pinned (phase/pin-verification-environment plan)
        expected (assoc (fixtures/fingerprint) :config-hash "candidate-config")
        request (fixtures/request pinned :candidate expected)]
    (is (= expected (phase/compare-verification-environment request)))
    (is (anomaly/anomaly?
          (phase/compare-verification-environment
            (assoc-in request [:comparison/observed :image-digests] {"catalog" "sha256:foreign"}))))))

(deftest ^{:stratum 0} exact-predeclared-changes-match-but-unrelated-drift-does-not-test
  (let [pinned (phase/pin-verification-environment (fixtures/plan))
        candidate (merge (fixtures/fingerprint) (fixtures/changes))
        request (fixtures/request pinned :candidate candidate)]
    (is (m/validate phase/VerificationEnvironmentComparisonRequest request))
    (is (identical? candidate (phase/compare-verification-environment request)))
    (doseq [[key value] [[:cluster "foreign"] [:node-pools ["foreign"]]
                         [:image-digests {"catalog" "sha256:foreign"}]
                         [:config-hash "undeclared-config"]]]
      (is (= :environment-drift
             (get-in (phase/compare-verification-environment
                       (assoc-in request [:comparison/observed key] value))
                     [:anomaly/data :comparison/reason]))))))

(deftest ^{:stratum 0} rollback-compares-original-baseline-not-candidate-test
  (let [plan (fixtures/plan)
        pinned (phase/pin-verification-environment plan)
        baseline (:comparison/baseline plan)
        candidate (merge baseline (:comparison/changes plan))]
    (is (identical? baseline
                   (phase/compare-verification-environment
                     (fixtures/request pinned :rollback baseline))))
    (is (= :environment-drift
           (get-in (phase/compare-verification-environment
                     (fixtures/request pinned :rollback candidate))
                   [:anomaly/data :comparison/reason])))))

(deftest ^{:stratum 0} independent-pin-rejects-rehashed-contract-and-stale-bindings-test
  (let [plan (fixtures/plan)
        pinned (phase/pin-verification-environment plan)
        baseline (:comparison/baseline plan)
        request (fixtures/request pinned :rollback baseline)
        changed (phase/pin-verification-environment (assoc plan :comparison/changes {}))]
    (is (= :contract-mismatch
           (get-in (phase/compare-verification-environment
                     (assoc request :comparison/pin changed))
                   [:anomaly/data :comparison/reason])))
    (is (= :contract-mismatch
           (get-in (phase/compare-verification-environment
                     (assoc-in request [:comparison/pin :comparison/contract
                                        :comparison/changes :config-hash] "substituted"))
                   [:anomaly/data :comparison/reason])))
    (doseq [[key value] (fixtures/stale-bindings)]
      (is (= :binding-mismatch
             (get-in (phase/compare-verification-environment
                       (assoc-in request [:comparison/binding key] value))
                     [:anomaly/data :comparison/reason]))))))

(deftest ^{:stratum 0} malformed-comparison-and-undeclared-fields-fail-closed-test
  (let [pinned (phase/pin-verification-environment (fixtures/plan))
        baseline (fixtures/fingerprint)
        request (fixtures/request pinned :rollback baseline)]
    (doseq [invalid [nil {}
                     (assoc request :comparison/stage :unexpected)
                     (dissoc request :comparison/expected-hash)
                     (assoc request :comparison/observed {})
                     (assoc-in request [:comparison/observed :config-hash] nil)
                     (assoc-in request [:comparison/observed :replicas] [])]]
      (is (= :invalid-input
             (:anomaly/type (phase/compare-verification-environment invalid)))))))

(deftest ^{:stratum 0} no-declared-changes-retains-exact-baseline-matching-test
  (let [plan (assoc (fixtures/plan) :comparison/changes {})
        pinned (phase/pin-verification-environment plan)
        baseline (:comparison/baseline plan)
        request (fixtures/request pinned :candidate baseline)]
    (is (identical? baseline (phase/compare-verification-environment request)))
    (is (anomaly/anomaly?
          (phase/compare-verification-environment
            (assoc-in request [:comparison/observed :config-hash] "undeclared"))))))

(comment ::predeclared-canonical-environment-contract)
