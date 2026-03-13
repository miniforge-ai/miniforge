(ns ai.miniforge.cli.workflow-recommendation-config-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-recommendation-config :as cfg]))

(defn- recommendation-prompt-resource
  []
  (-> "config/workflow/recommendation-prompt.edn"
      io/resource
      slurp
      edn/read-string
      :workflow-recommendation/prompt))

(deftest recommendation-prompt-config-test
  (testing "software-factory prompt config loads from resources"
    (let [config (cfg/recommendation-prompt-config)
          prompt-resource (recommendation-prompt-resource)]
      (is (= (last (:analysis-dimensions prompt-resource))
             (last (:analysis-dimensions config))))
      (is (= (get-in prompt-resource [:summary-labels :has-review])
             (get-in config [:summary-labels :has-review])))
      (is (= (get-in prompt-resource [:summary-labels :has-testing])
             (get-in config [:summary-labels :has-testing]))))))
