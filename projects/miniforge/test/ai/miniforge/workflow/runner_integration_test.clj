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

(deftest run-pipeline-max-phases-test
  (testing "run-pipeline respects max phases option"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline
                    [{:phase :plan}
                     {:phase :implement}
                     {:phase :verify}
                     {:phase :review}
                     {:phase :release}
                     {:phase :done}]}
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
