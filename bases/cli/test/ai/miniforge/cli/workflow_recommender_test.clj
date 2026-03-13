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
      (let [prompt-config (cfg/recommendation-prompt-config)]
        (with-redefs [cfg/recommendation-prompt-config
                      (fn []
                        prompt-config)
                      recommender/build-workflow-summaries
                      (fn [_workflows]
                        (str "- workflow\n  "
                             (get-in prompt-config [:summary-labels :has-review]) "\n  "
                             (get-in prompt-config [:summary-labels :has-testing])))]
          (let [prompt (recommender/build-recommendation-prompt
                        {:spec/title "Fix flaky test"}
                        available-workflows)]
            (is (.contains prompt (last (:analysis-dimensions prompt-config))))
            (is (.contains prompt (get-in prompt-config [:summary-labels :has-review])))
            (is (.contains prompt (get-in prompt-config [:summary-labels :has-testing])))))))

    (testing "generic fallback vocabulary is available when no app config is present"
      (let [prompt-config cfg/default-prompt-config]
        (with-redefs [cfg/recommendation-prompt-config
                      (fn []
                        prompt-config)
                      recommender/build-workflow-summaries
                      (fn [_workflows]
                        (str "- workflow\n  "
                             (get-in prompt-config [:summary-labels :has-review]) "\n  "
                             (get-in prompt-config [:summary-labels :has-testing])))]
          (let [prompt (recommender/build-recommendation-prompt
                        {:spec/title "Run analytical workflow"}
                        available-workflows)]
            (is (.contains prompt (last (:analysis-dimensions prompt-config))))
            (is (.contains prompt (get-in prompt-config [:summary-labels :has-review])))
            (is (.contains prompt (get-in prompt-config [:summary-labels :has-testing])))))))))

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
