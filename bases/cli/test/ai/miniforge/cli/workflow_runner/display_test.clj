(ns ai.miniforge.cli.workflow-runner.display-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.workflow-runner.display :as sut]))

(deftest print-workflow-header-uses-app-display-name-test
  (testing "workflow runner header uses the active app display name"
    (with-redefs [app-config/display-name (constantly "Workflow Kernel")]
      (let [output (with-out-str (sut/print-workflow-header :simple-v2 "latest" false))]
        (is (.contains output "Workflow Kernel Workflow Runner"))
        (is (.contains output "Workflow: "))
        (is (.contains output "simple-v2"))))))
