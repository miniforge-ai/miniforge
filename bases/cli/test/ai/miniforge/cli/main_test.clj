(ns ai.miniforge.cli.main-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main :as sut]))

(deftest help-cmd-uses-generic-workflow-examples-test
  (testing "CLI help shows generic workflow examples instead of SDLC-specific ones"
    (let [output (with-out-str (sut/help-cmd {}))]
      (is (.contains output "miniforge - AI-powered software development workflows"))
      (is (.contains output "miniforge workflow run :simple-v2"))
      (is (.contains output "miniforge workflow run :financial-etl -i input.edn"))
      (is (.contains output "miniforge chain list"))
      (is (not (.contains output "canonical-sdlc-v1")))
      (is (not (.contains output "Build feature"))))))

(deftest help-cmd-reads-copy-from-message-catalog-test
  (testing "help output is assembled from message resources rather than hardcoded strings"
    (with-redefs [app-config/binary-name (constantly "engine")
                  app-config/description (constantly "desc")
                  app-config/tui-package (constantly "engine-tui")
                  app-config/help-examples (constantly ["run sample"])
                  app-config/command-string identity
                  messages/t (fn
                               ([k]
                                (case k
                                  :help/command-lines ["CMD:one" "CMD:two"]
                                  (str "UNEXPECTED:" k)))
                               ([k params]
                                (case k
                                  :help/title (str "TITLE:" (:binary params) ":" (:description params))
                                  :help/usage (str "USAGE:" (:binary params))
                                  :help/note (str "NOTE:" (:binary params))
                                  :help/tui-install (str "TUI:" (:tui-package params))
                                  (str "UNEXPECTED:" k))))]
      (let [output (with-out-str (sut/help-cmd {}))]
        (is (.contains output "TITLE:engine:desc"))
        (is (.contains output "USAGE:engine"))
        (is (.contains output "CMD:one"))
        (is (.contains output "NOTE:engine"))
        (is (.contains output "TUI:engine-tui"))))))
