(ns ai.miniforge.cli.workflow-runner.display-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display :as sut]))

(deftest print-workflow-header-uses-app-display-name-test
  (testing "workflow runner header uses the active app display name"
    (with-redefs [app-config/display-name (constantly "MiniForge Core")]
      (let [output (with-out-str (sut/print-workflow-header :simple-v2 "latest" false))]
        (is (.contains output
                       (messages/t :workflow-runner/header
                                   {:display-name (app-config/display-name)})))
        (is (.contains output
                       (messages/t :workflow-runner/workflow
                                   {:workflow-id "simple-v2"})))
        (is (.contains output
                       (messages/t :workflow-runner/version
                                   {:version "latest"})))))))

(deftest workflow-runner-event-lines-use-message-catalog-test
  (testing "event formatting reads labels from the message catalog"
    (with-redefs [messages/t (fn
                               ([k]
                                (case k
                                  :workflow-runner/default-status "WORKING"
                                  (name k)))
                               ([k params]
                                (case k
                                  :workflow-runner/phase-completed
                                  (str "PHASE:" (name (:phase params)) ":" (:outcome params))
                                  (name k))))]
      (let [line (sut/format-event-line {:event/type :workflow/phase-completed
                                         :phase :extract
                                         :phase/outcome :completed})]
        (is (.contains line "PHASE:extract:completed"))))))

(deftest workflow-runner-error-help-uses-message-catalog-test
  (testing "error help output is assembled from message resources"
    (with-redefs [messages/t (fn
                               ([k]
                                (case k
                                  :workflow-runner/load-failed "LOAD-FAILED"
                                  :workflow-runner/possible-causes "POSSIBLE"
                                  :workflow-runner/cause-missing-dep "CAUSE-DEP"
                                  :workflow-runner/cause-compile "CAUSE-COMPILE"
                                  :workflow-runner/cause-cycle "CAUSE-CYCLE"
                                  (str "UNEXPECTED:" k)))
                               ([k params]
                                (case k
                                  :workflow-runner/error (str "ERR:" (:message params))
                                  :workflow-runner/details (str "DETAILS:" (:details params))
                                  :workflow-runner/cause (str "CAUSE:" (:cause params))
                                  (str "UNEXPECTED:" k))))]
      (let [output (with-out-str
                     (sut/print-error-header "boom" {:a 1} (ex-info "bad" {})))]
        (is (.contains output "LOAD-FAILED"))
        (is (.contains output "ERR:boom"))
        (is (.contains output "DETAILS:{:a 1}"))
        (is (.contains output "CAUSE:bad"))
        (is (.contains output "CAUSE-CYCLE"))))))
