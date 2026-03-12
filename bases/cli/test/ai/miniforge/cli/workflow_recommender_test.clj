(ns ai.miniforge.cli.workflow-recommender-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-recommender :as recommender]))

(deftest recommend-by-task-type-test
  (let [available-workflows [{:workflow/id :lean-sdlc-v1
                              :workflow/task-types [:bugfix :docs]}
                             {:workflow/id :canonical-sdlc-v1
                              :workflow/task-types [:feature :refactoring]}]]
    (testing "matching task types win before profile fallback"
      (let [result (recommender/recommend-by-task-type
                    {:spec/workflow-type :bugfix}
                    available-workflows)]
        (is (= :lean-sdlc-v1 (:workflow result)))
        (is (= :fallback (:source result)))))

    (testing "missing task types fall back to the app-configured default workflow"
      (let [result (recommender/recommend-by-task-type
                    {:spec/workflow-type :unmapped-task}
                    available-workflows)]
        (is (= :lean-sdlc-v1 (:workflow result)))
        (is (= :fallback (:source result)))
        (is (= 0.3 (:confidence result)))))))
