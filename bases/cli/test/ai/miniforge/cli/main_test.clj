(ns ai.miniforge.cli.main-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main :as sut]))

(deftest help-cmd-uses-generic-workflow-examples-test
  (testing "CLI help shows generic workflow examples instead of SDLC-specific ones"
    (let [output (with-out-str (sut/help-cmd {}))]
      (is (.contains output "miniforge workflow run :simple-v2"))
      (is (.contains output "miniforge workflow run :financial-etl -i input.edn"))
      (is (.contains output "miniforge chain list"))
      (is (not (.contains output "canonical-sdlc-v1")))
      (is (not (.contains output "Build feature"))))))
