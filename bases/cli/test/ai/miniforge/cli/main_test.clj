;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.cli.main-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main :as sut]
   [ai.miniforge.cli.main.commands.pr :as cmd-pr]))

(deftest help-cmd-uses-generic-workflow-examples-test
  (testing "CLI help shows generic workflow examples instead of SDLC-specific ones"
    (let [output (with-out-str (sut/help-cmd {}))
          title (messages/t :help/title {:binary (app-config/binary-name)
                                         :description (app-config/description)})]
      (is (.contains output title))
      (doseq [example (app-config/help-examples)]
        (is (.contains output (app-config/command-string example))))
      (is (not (.contains output "canonical-sdlc-v1"))))))

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

;------------------------------------------------------------------------------ Layer 1
;; Dispatch table coverage

(deftest dispatch-table-includes-pr-monitor-test
  (testing "pr monitor command is registered in dispatch table"
    (let [entries (filter #(= ["pr" "monitor"] (:cmds %)) sut/dispatch-table)]
      (is (= 1 (count entries)) "Exactly one pr monitor entry")
      (is (some? (:fn (first entries))) "Has a handler function")
      (is (= {:author {:alias :a} :poll-interval {:alias :p}}
             (:spec (first entries)))
          "Spec includes --author and --poll-interval"))))

;------------------------------------------------------------------------------ Layer 1
;; pr-monitor-cmd helpers

(deftest parse-poll-interval-test
  (testing "Valid interval returns milliseconds"
    (is (= 30000 (#'cmd-pr/parse-poll-interval "30" 60000))))
  (testing "Nil interval returns default"
    (is (= 60000 (#'cmd-pr/parse-poll-interval nil 60000))))
  (testing "Out-of-bounds interval returns default with error message"
    (let [output (with-out-str
                   (is (= 60000 (#'cmd-pr/parse-poll-interval "1" 60000))))]
      (is (re-find #"5-3600" output))))
  (testing "Non-numeric interval returns default with error message"
    (let [output (with-out-str
                   (is (= 60000 (#'cmd-pr/parse-poll-interval "abc" 60000))))]
      (is (re-find #"Invalid" output)))))
