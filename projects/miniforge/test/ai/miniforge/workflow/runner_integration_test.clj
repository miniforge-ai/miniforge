(ns ai.miniforge.workflow.runner-integration-test
  "Integration tests for the workflow runner that execute real phase pipelines."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner :as runner]
   ;; Require phase implementations
   [ai.miniforge.phase.plan]
   [ai.miniforge.phase.implement]
   [ai.miniforge.phase.verify]
   [ai.miniforge.phase.review]
   [ai.miniforge.phase.release]
   ;; Require gate implementations
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.test]
   [ai.miniforge.gate.policy]))

(defn with-mocked-test-runner
  "Run body-fn with run-tests! and write-test-files! mocked to prevent recursive bb test."
  [body-fn]
  (let [write-var (resolve 'ai.miniforge.phase.verify/write-test-files!)
        run-var (resolve 'ai.miniforge.phase.verify/run-tests!)]
    (with-redefs-fn
      {write-var (fn [_ files] (mapv :path files))
       run-var (fn [_] {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
      body-fn)))

(deftest run-pipeline-max-phases-test
  (testing "run-pipeline respects max phases option"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {:max-phases 50})]
      (is (= :completed (:execution/status result))))))

(deftest response-chain-records-phases-test
  (testing "response chain records phase results"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {})
          chain (:execution/response-chain result)]
      (is (= :completed (:execution/status result)))
      (is (>= (count (:response-chain chain)) 2))
      (is (true? (:succeeded? chain))))))
