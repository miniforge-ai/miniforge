(ns ai.miniforge.workflow.registry-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.registry :as registry]))

(use-fixtures :each
  (fn [f]
    (registry/clear-registry!)
    (f)
    (registry/clear-registry!)))

(deftest discover-workflows-from-resources-test
  (testing "workflow discovery reflects the workflows on the active classpath"
    (let [workflow-ids (->> (registry/discover-workflows-from-resources)
                            (map :workflow/id)
                            set)]
      (is (contains? workflow-ids :financial-etl))
      (is (contains? workflow-ids :simple-test-v1))
      (is (contains? workflow-ids :canonical-sdlc-v1)))))

(deftest initialize-registry!-test
  (testing "registry initialization no longer depends on a shared registry config file"
    (let [count (registry/initialize-registry!)
          workflow-ids (set (registry/list-workflow-ids))]
      (is (pos? count))
      (is (contains? workflow-ids :financial-etl))
      (is (contains? workflow-ids :standard-sdlc))
      (is (contains? workflow-ids :simple)))))
