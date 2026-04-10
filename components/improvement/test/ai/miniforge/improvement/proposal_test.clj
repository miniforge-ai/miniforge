;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.improvement.proposal-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.improvement.interface :as imp]))

(deftest generate-proposal-test
  (testing "creates proposal from diagnosis"
    (let [diagnosis {:diagnosis/id (random-uuid)
                     :diagnosis/hypothesis "Recurring timeouts"
                     :diagnosis/confidence 0.7
                     :diagnosis/affected-heuristic :threshold/timeout
                     :diagnosis/suggested-improvement-type :threshold-adjustment}
          proposal (imp/generate-proposal diagnosis)]
      (is (uuid? (:improvement/id proposal)))
      (is (= :threshold-adjustment (:improvement/type proposal)))
      (is (= :threshold/timeout (:improvement/target proposal)))
      (is (= :proposed (:improvement/status proposal)))
      (is (= 0.7 (:improvement/confidence proposal))))))

(deftest generate-proposals-filters-low-confidence-test
  (let [diagnoses [{:diagnosis/id (random-uuid) :diagnosis/confidence 0.5
                    :diagnosis/hypothesis "A"}
                   {:diagnosis/id (random-uuid) :diagnosis/confidence 0.2
                    :diagnosis/hypothesis "B"}
                   {:diagnosis/id (random-uuid) :diagnosis/confidence 0.8
                    :diagnosis/hypothesis "C"}]]
    (testing "default min-confidence 0.3"
      (is (= 2 (count (imp/generate-proposals diagnoses)))))
    (testing "custom min-confidence"
      (is (= 1 (count (imp/generate-proposals diagnoses {:min-confidence 0.6})))))))

(deftest pipeline-lifecycle-test
  (let [pipeline (imp/create-pipeline)
        proposal (imp/generate-proposal
                  {:diagnosis/id (random-uuid) :diagnosis/confidence 0.7
                   :diagnosis/hypothesis "Test" :diagnosis/affected-heuristic :test})]
    (testing "store and retrieve"
      (imp/store-proposal! pipeline proposal)
      (is (= 1 (count (imp/get-proposals pipeline)))))

    (testing "approve"
      (imp/approve-proposal! pipeline (:improvement/id proposal))
      (is (= :approved (:improvement/status (first (imp/get-proposals pipeline :approved))))))

    (testing "deploy"
      (imp/deploy-proposal! pipeline (:improvement/id proposal))
      (is (= :deployed (:improvement/status (first (imp/get-proposals pipeline :deployed))))))

    (testing "rollback"
      (imp/rollback-proposal! pipeline (:improvement/id proposal) "regression detected")
      (is (= :rolled-back (:improvement/status
                            (first (imp/get-proposals pipeline :rolled-back))))))))
