;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.training-capture.quality-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.training-capture.interface :as tc]))

;; ---------------------------------------------------------------------------- Quality score computation

(deftest quality-score-test
  (testing "perfect pass on first try with phase success"
    (let [score (tc/compute-quality-score
                 {:validation-result :passed
                  :iterations-to-pass 1
                  :phase-succeeded? true})]
      (is (= 1.0 score))))

  (testing "pass after 4 iterations"
    (let [score (tc/compute-quality-score
                 {:validation-result :passed
                  :iterations-to-pass 4
                  :phase-succeeded? true})]
      ;; 1.0 - 0.3 (3 extra iterations * 0.1) + 0.1 (phase bonus) ≈ 0.8
      (is (< (abs (- 0.8 score)) 0.001))))

  (testing "escalated failure"
    (let [score (tc/compute-quality-score
                 {:validation-result :failed
                  :escalated? true})]
      ;; 0.0 - 0.3 = clamped to 0.0
      (is (= 0.0 score))))

  (testing "passed but with production incident"
    (let [score (tc/compute-quality-score
                 {:validation-result :passed
                  :iterations-to-pass 1
                  :production-incident? true
                  :phase-succeeded? true})]
      ;; 1.0 - 0.5 + 0.1 = 0.6
      (is (== 0.6 score))))

  (testing "partial validation with human override"
    (let [score (tc/compute-quality-score
                 {:validation-result :partial
                  :human-override? true
                  :iterations-to-pass 1})]
      ;; 0.5 - 0.2 = 0.3
      (is (== 0.3 score))))

  (testing "never goes below 0.0"
    (is (= 0.0 (tc/compute-quality-score
                 {:validation-result :failed
                  :escalated? true
                  :human-override? true
                  :production-incident? true}))))

  (testing "never goes above 1.0"
    (is (= 1.0 (tc/compute-quality-score
                 {:validation-result :passed
                  :iterations-to-pass 1
                  :phase-succeeded? true})))))

;; ---------------------------------------------------------------------------- Labeling

(deftest label-example-test
  (testing "positive: high quality, no corrections"
    (is (= :positive (tc/label-example 0.9 {}))))

  (testing "negative: low quality"
    (is (= :negative (tc/label-example 0.1 {}))))

  (testing "corrected: human provided correction"
    (is (= :corrected (tc/label-example 0.9 {:human-correction "fixed it"}))))

  (testing "hard-positive: passed after many iterations"
    (is (= :hard-positive (tc/label-example 0.85 {:iterations-to-pass 3}))))

  (testing "ambiguous: medium quality"
    (is (= :ambiguous (tc/label-example 0.5 {}))))

  (testing "boundary: 0.8 is positive"
    (is (= :positive (tc/label-example 0.8 {}))))

  (testing "boundary: 0.3 is ambiguous"
    (is (= :ambiguous (tc/label-example 0.3 {}))))

  (testing "boundary: 0.29 is negative"
    (is (= :negative (tc/label-example 0.29 {})))))

;; ---------------------------------------------------------------------------- Training record creation

(deftest create-training-record-test
  (testing "creates complete record with quality score and label"
    (let [record (tc/create-training-record
                  {:agent-role :implementer
                   :workflow-id (random-uuid)
                   :phase :implement
                   :feedback {:validation-result :passed
                              :iterations-to-pass 1}
                   :outcome {:phase-succeeded? true}})]
      (is (uuid? (:training/id record)))
      (is (inst? (:training/timestamp record)))
      (is (= :implementer (:training/agent-role record)))
      (is (number? (get-in record [:training/labels :quality-score])))
      (is (keyword? (get-in record [:training/labels :example-type]))))))

;; ---------------------------------------------------------------------------- Store

(deftest store-test
  (let [store (tc/create-store)
        record (tc/create-training-record
                {:agent-role :implementer
                 :workflow-id (random-uuid)
                 :phase :implement
                 :feedback {:validation-result :passed :iterations-to-pass 1}
                 :outcome {:phase-succeeded? true}})]
    (testing "store and retrieve"
      (tc/store-example! store record)
      (is (= 1 (tc/example-count store)))
      (is (= 1 (count (tc/get-examples store)))))

    (testing "filter by agent-role"
      (tc/store-example! store
                         (tc/create-training-record
                          {:agent-role :tester
                           :workflow-id (random-uuid)
                           :phase :verify
                           :feedback {:validation-result :passed :iterations-to-pass 1}
                           :outcome {:phase-succeeded? true}}))
      (is (= 1 (count (tc/get-examples store {:agent-role :tester}))))
      (is (= 1 (count (tc/get-examples store {:agent-role :implementer})))))

    (testing "filter by min-quality"
      (tc/store-example! store
                         (tc/create-training-record
                          {:agent-role :implementer
                           :workflow-id (random-uuid)
                           :phase :implement
                           :feedback {:validation-result :failed}
                           :outcome {}}))
      (is (= 2 (count (tc/get-examples store {:min-quality 0.8}))))
      (is (= 3 (count (tc/get-examples store {:min-quality 0.0})))))))
