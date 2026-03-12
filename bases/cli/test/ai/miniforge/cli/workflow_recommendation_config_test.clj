(ns ai.miniforge.cli.workflow-recommendation-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-recommendation-config :as cfg]))

(deftest recommendation-prompt-config-test
  (testing "software-factory prompt config loads from resources"
    (let [config (cfg/recommendation-prompt-config)]
      (is (= "Task type (feature, bugfix, refactor, test)"
             (last (:analysis-dimensions config))))
      (is (= "Includes code review"
             (get-in config [:summary-labels :has-review])))
      (is (= "Includes testing"
             (get-in config [:summary-labels :has-testing]))))))
