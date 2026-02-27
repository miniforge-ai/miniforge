(ns ai.miniforge.evidence-bundle.chain-evidence-test
  "Tests for chain-level evidence aggregation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.interface :as evidence]))

;------------------------------------------------------------------------------ Test Data

(def test-chain-def
  {:chain/id :test-chain
   :chain/version "1.0.0"
   :chain/steps [{:step/id :plan :step/workflow-id :planning}
                 {:step/id :implement :step/workflow-id :implementation}]})

(def test-chain-result
  {:chain/id :test-chain
   :chain/status :completed
   :chain/duration-ms 5000
   :chain/step-results [{:step/id :plan
                         :step/workflow-id :planning
                         :step/status :completed
                         :step/output {:phase-results {:plan {:result "ok"}}}}
                        {:step/id :implement
                         :step/workflow-id :implementation
                         :step/status :completed
                         :step/output nil}]})

(def test-failed-chain-result
  {:chain/id :test-chain
   :chain/status :failed
   :chain/duration-ms 2000
   :chain/step-results [{:step/id :plan
                         :step/workflow-id :planning
                         :step/status :completed
                         :step/output {:phase-results {:plan {:result "ok"}}}}
                        {:step/id :implement
                         :step/workflow-id :implementation
                         :step/status :failed
                         :step/output nil}]})

;------------------------------------------------------------------------------ create-chain-evidence Tests

(deftest create-chain-evidence-success-test
  (testing "Creates evidence with all required fields for successful chain"
    (let [ev (evidence/create-chain-evidence test-chain-def test-chain-result)]
      (is (uuid? (:chain-evidence/id ev)))
      (is (= :test-chain (:chain-evidence/chain-id ev)))
      (is (= "1.0.0" (:chain-evidence/chain-version ev)))
      (is (= 2 (:chain-evidence/step-count ev)))
      (is (= :completed (:chain-evidence/status ev)))
      (is (= 5000 (:chain-evidence/duration-ms ev)))
      (is (= 2 (count (:chain-evidence/step-summaries ev))))
      (is (inst? (:chain-evidence/created-at ev))))))

(deftest create-chain-evidence-failed-test
  (testing "Creates evidence with :failed status for failed chain"
    (let [ev (evidence/create-chain-evidence test-chain-def test-failed-chain-result)]
      (is (= :failed (:chain-evidence/status ev)))
      (is (= 2000 (:chain-evidence/duration-ms ev))))))

(deftest create-chain-evidence-step-bundles-test
  (testing "Includes step-bundles when provided in opts"
    (let [bundle-ids [#uuid "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
                      #uuid "11111111-2222-3333-4444-555555555555"]
          ev (evidence/create-chain-evidence test-chain-def test-chain-result
                                             {:step-bundles bundle-ids})]
      (is (= bundle-ids (:chain-evidence/step-bundles ev))))))

;------------------------------------------------------------------------------ summarize-step Tests

(deftest summarize-step-with-output-test
  (testing "Step with output has :step/has-output? true"
    (let [step-result {:step/id :plan
                       :step/workflow-id :planning
                       :step/status :completed
                       :step/output {:result "ok"}}
          summary (evidence/summarize-step step-result 0)]
      (is (= :plan (:step/id summary)))
      (is (= :planning (:step/workflow-id summary)))
      (is (= 0 (:step/index summary)))
      (is (= :completed (:step/status summary)))
      (is (true? (:step/has-output? summary))))))

(deftest summarize-step-without-output-test
  (testing "Step without output has :step/has-output? false"
    (let [step-result {:step/id :implement
                       :step/workflow-id :implementation
                       :step/status :completed
                       :step/output nil}
          summary (evidence/summarize-step step-result 1)]
      (is (false? (:step/has-output? summary))))))

;------------------------------------------------------------------------------ aggregate-metrics Tests

(deftest aggregate-metrics-mixed-test
  (testing "Correctly counts completed, failed, and output steps"
    (let [step-results [{:step/id :a :step/status :completed :step/output {:x 1}}
                        {:step/id :b :step/status :completed :step/output nil}
                        {:step/id :c :step/status :failed :step/output nil}]
          metrics (evidence/aggregate-metrics step-results)]
      (is (= 3 (:total-steps metrics)))
      (is (= 2 (:completed-steps metrics)))
      (is (= 1 (:failed-steps metrics)))
      (is (= 1 (:steps-with-output metrics))))))
