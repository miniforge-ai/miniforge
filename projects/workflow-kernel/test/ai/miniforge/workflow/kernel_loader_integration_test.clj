(ns ai.miniforge.workflow.kernel-loader-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main :as main]
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

(deftest kernel-project-overrides-cli-identity
  (testing "kernel project uses its own app profile"
    (is (= "workflow-kernel" (app-config/binary-name)))
    (is (.endsWith (app-config/config-path) "/.workflow-kernel/config.edn")))
  (testing "kernel help uses kernel-specific copy"
    (let [output (with-out-str (main/help-cmd {}))]
      (is (.contains output "workflow-kernel - Local governed workflow engine"))
      (is (.contains output "workflow-kernel workflow list"))
      (is (not (.contains output "workflow-kernel workflow run :financial-etl -i input.edn"))))))
