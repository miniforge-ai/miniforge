(ns ai.miniforge.cli.workflow-recommender-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-recommendation-config :as cfg]
   [ai.miniforge.cli.workflow-recommender :as recommender]))

(deftest build-recommendation-prompt-test
  (let [available-workflows [{:workflow/id :lean-sdlc-v1
                              :workflow/task-types [:bugfix :docs]}
                             {:workflow/id :canonical-sdlc-v1
                              :workflow/task-types [:feature :refactoring]}]]
    (testing "configured prompt vocabulary is reflected in the assembled prompt"
      (with-redefs [cfg/recommendation-prompt-config
                    (fn []
                      {:analysis-dimensions
                       ["Task complexity (simple, medium, complex)"
                        "Task type (feature, bugfix, refactor, test)"]
                       :summary-labels
                       {:has-review "Includes code review"
                        :has-testing "Includes testing"}})
                    recommender/build-workflow-summaries
                    (fn [_workflows]
                      "- workflow\n  Includes code review\n  Includes testing")]
        (let [prompt (recommender/build-recommendation-prompt
                      {:spec/title "Fix flaky test"}
                      available-workflows)]
          (is (.contains prompt "Task type (feature, bugfix, refactor, test)"))
          (is (.contains prompt "Includes code review"))
          (is (.contains prompt "Includes testing")))))

    (testing "generic fallback vocabulary is available when no app config is present"
      (with-redefs [cfg/recommendation-prompt-config
                    (fn []
                      cfg/default-prompt-config)
                    recommender/build-workflow-summaries
                    (fn [_workflows]
                      "- workflow\n  Includes review checkpoints\n  Includes verification/testing")]
        (let [prompt (recommender/build-recommendation-prompt
                      {:spec/title "Run analytical workflow"}
                      available-workflows)]
          (is (.contains prompt "Work category and expected outputs"))
          (is (.contains prompt "Includes review checkpoints"))
          (is (.contains prompt "Includes verification/testing")))))))

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
