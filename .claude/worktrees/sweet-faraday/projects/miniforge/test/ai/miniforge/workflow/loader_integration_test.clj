(ns ai.miniforge.workflow.loader-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.loader :as loader]))

(deftest load-workflow-integration-test
  (testing "complete workflow loading and validation flow"
    (loader/clear-cache!)
    (let [result (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
      (is (map? result))
      (is (contains? result :workflow))
      (is (contains? result :source))
      (is (contains? result :validation))
      (let [workflow (:workflow result)]
        (is (= :canonical-sdlc-v1 (:workflow/id workflow)))
        (is (= "1.0.0" (:workflow/version workflow)))
        (is (= :feature (:workflow/type workflow)))
        (is (vector? (:workflow/phases workflow)))
        (is (seq (:workflow/phases workflow)))
        (is (keyword? (:workflow/entry-phase workflow)))
        (is (vector? (:workflow/exit-phases workflow))))
      (is (true? (get-in result [:validation :valid?])))
      (is (empty? (get-in result [:validation :errors])))
      (let [result2 (loader/load-workflow :canonical-sdlc-v1 "1.0.0" {})]
        (is (= :cache (:source result2)))
        (is (= (:workflow result) (:workflow result2)))))))
