;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.workflow.definition-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.definition :as definition]))

(deftest ensure-execution-pipeline-test
  (testing "legacy phase workflows are normalized into pipeline entries"
    (let [workflow {:workflow/phases [{:phase/id :plan
                                       :phase/next [{:target :implement}]}
                                      {:phase/id :implement
                                       :phase/next [{:target :done}]}
                                      {:phase/id :done
                                       :phase/next []}]}
          normalized (definition/ensure-execution-pipeline workflow)]
      (is (= [{:phase :plan :on-success :implement}
              {:phase :implement :on-success :done}
              {:phase :done}]
             (:workflow/pipeline normalized)))))

  (testing "existing pipeline workflows are preserved"
    (let [workflow {:workflow/pipeline [{:phase :plan}
                                        {:phase :done}]}
          normalized (definition/ensure-execution-pipeline workflow)]
      (is (= workflow normalized)))))
