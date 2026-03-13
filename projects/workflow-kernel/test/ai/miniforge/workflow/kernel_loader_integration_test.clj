(ns ai.miniforge.workflow.kernel-loader-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.interface :as workflow]))

(deftest kernel-project-loads-only-kernel-workflows
  (let [workflow-ids (->> (workflow/list-workflows)
                          (map :workflow/id)
                          set)]
    (testing "kernel project loads generic workflows"
      (is (contains? workflow-ids :simple-test-v1))
      (is (contains? workflow-ids :minimal-test-v1)))
    (testing "kernel project does not ship product workflows"
      (is (not (contains? workflow-ids :canonical-sdlc-v1)))
      (is (not (contains? workflow-ids :financial-etl))))))
