;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.replica-comparison-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.replica-contract-fixtures :as contract]
            [ai.miniforge.phase-opsv.replica-comparison-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reason [request]
  (get-in (phase/compare-verification-environment request)
          [:anomaly/data :comparison/reason]))

(deftest ^{:stratum 0} bounded-replacement-identities-do-not-establish-drift-test
  (let [plan (contract/plan)]
    (doseq [ids [["replacement-a"] ["replacement-a" "replacement-b"]
                 ["replacement-a" "replacement-b" "replacement-c"]]]
      (let [request (fixtures/request plan :candidate ids)]
        (is (identical? (:comparison/observed request)
                       (phase/compare-verification-environment request)))))))

(deftest ^{:stratum 0} expectations-cannot-expand-the-bound-target-or-invariant-state-test
  (let [plan (contract/plan)]
    (doseq [stage [:candidate :rollback]
            [path value] [[[:replica/target :workload-uid] "foreign"]
                          [[:replica/node-pools] ["foreign"]]
                          [[:replica/image-digests] {"catalog" "foreign"}]
                          [[:replicas/min-count] (inc contract/maximum-candidate-replicas)]]]
      (is (= :invalid-input
             (:anomaly/type
               (phase/pin-verification-environment
                 (assoc-in plan (into [:comparison/replica-contract stage] path) value))))))))

(deftest ^{:stratum 0} population-observations-without-declared-rules-are-not-ignored-test
  (let [plan (dissoc (contract/plan) :comparison/replica-contract)
        request (fixtures/request plan :candidate [])]
    (is (= :invalid-input (:anomaly/type (phase/compare-verification-environment request))))))

(deftest ^{:stratum 0} placement-drift-retains-the-declared-vector-in-evidence-test
  (let [plan (contract/plan)
        request (fixtures/request plan :candidate ["replacement"])
        foreign (assoc-in request [:comparison/replica-observations 0 :replica/node-pool] "foreign")
        result (phase/compare-verification-environment foreign)
        expected (get-in plan [:comparison/replica-contract :candidate :replica/node-pools])]
    (is (= expected (get-in result [:anomaly/data :comparison/expected])))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} population-bounds-and-duplicate-identities-fail-closed-test
  (let [plan (contract/plan)
        excess-count (inc contract/maximum-candidate-replicas)
        excess-ids (mapv str (range excess-count))]
    (is (= :replica-count-drift (reason (fixtures/request plan :candidate []))))
    (is (= :replica-count-drift (reason (fixtures/request plan :candidate excess-ids))))
    (is (= :duplicate-replica (reason (fixtures/request plan :candidate ["same" "same"]))))))

(deftest ^{:stratum 1} foreign-membership-and-runtime-drift-are-not-churn-test
  (let [request (fixtures/request (contract/plan) :candidate ["replacement"])]
    (doseq [path [[:replica/target :cluster] [:replica/target :namespace]
                  [:replica/target :namespace-uid] [:replica/target :workload-uid]
                  [:replica/image-digests "catalog"]]]
      (is (= :replica-drift
             (reason (assoc-in request (into [:comparison/replica-observations 0] path) "foreign")))))
    (is (= :replica-placement-drift
           (reason (assoc-in request [:comparison/replica-observations 0 :replica/node-pool] "foreign"))))
    (is (= :replica-drift
           (reason (assoc-in request [:comparison/replica-observations 0 :replica/template-hash]
                             (fixtures/other-template)))))))

(deftest ^{:stratum 1} recovery-has-independent-template-and-population-expectations-test
  (let [plan (contract/plan)
        recovery (fixtures/request plan :rollback ["replacement"])
        candidate (fixtures/request plan :candidate ["replacement"])]
    (is (= (:comparison/observed recovery) (phase/compare-verification-environment recovery)))
    (is (= :replica-count-drift (reason (fixtures/request plan :rollback ["a" "b"]))))
    (is (= :replica-drift
           (reason (assoc recovery :comparison/replica-observations
                          (:comparison/replica-observations candidate)))))))

(deftest ^{:stratum 1} replica-contract-cannot-be-dropped-or-substituted-test
  (let [plan (contract/plan)
        request (fixtures/request plan :candidate ["replacement"])]
    (is (= :invalid-input
           (:anomaly/type (phase/compare-verification-environment
                            (dissoc request :comparison/replica-observations)))))
    (is (= :contract-mismatch
           (reason (assoc-in request [:comparison/pin :comparison/contract
                                      :comparison/replica-contract :candidate :replicas/max-count]
                             (inc contract/maximum-candidate-replicas)))))
    (is (anomaly/anomaly?
          (phase/pin-verification-environment
            (assoc-in plan [:comparison/replica-contract :candidate :replicas/max-count] -1))))))

(deftest ^{:stratum 1} zero-replicas-require-an-explicit-zero-bound-test
  (let [plan (-> (contract/plan)
                 (assoc-in [:comparison/replica-contract :candidate :replicas/min-count] 0)
                 (assoc-in [:comparison/replica-contract :candidate :replicas/max-count] 0))
        request (fixtures/request plan :candidate [])]
    (is (= (:comparison/observed request) (phase/compare-verification-environment request)))
    (is (= :replica-count-drift (reason (fixtures/request plan :candidate ["replacement"]))))))

(comment ::population-match-is-not-a-verification-verdict)
