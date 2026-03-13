(ns ai.miniforge.workflow.kernel-loader-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
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
  (let [profile (app-config/app-profile)]
    (testing "kernel project uses its own app profile"
      (is (= (:name profile) (app-config/binary-name)))
      (is (.endsWith (app-config/config-path)
                     (str "/" (:home-dir-name profile) "/config.edn"))))
    (testing "kernel help uses kernel-specific copy"
      (let [output (with-out-str (main/help-cmd {}))
            title (messages/t :help/title {:binary (app-config/binary-name)
                                           :description (app-config/description)})]
        (is (.contains output title))
        (doseq [example (app-config/help-examples)]
          (is (.contains output (app-config/command-string example))))
        (is (not (.contains output (app-config/command-string
                                    "workflow run :financial-etl -i input.edn"))))))))
