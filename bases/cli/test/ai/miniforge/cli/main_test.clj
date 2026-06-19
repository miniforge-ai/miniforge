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
   [ai.miniforge.cli.main.commands.pr-monitor :as cmd-pr-monitor]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.repo-dag.interface :as repo-dag]
   [ai.miniforge.workflow-resume.interface :as wr]))

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

(deftest create-pr-train-manager-handles-construction-errors-test
  (testing "manager construction failure logs a warning and returns nil"
    (with-redefs [pr-train/create-manager
                  (fn [] (throw (ex-info "train boom" {})))]
      (let [output (with-out-str
                     (is (nil? (#'sut/create-pr-train-manager))))]
        (is (.contains output "train boom"))))))

(deftest create-repo-dag-manager-handles-construction-errors-test
  (testing "manager construction failure logs a warning and returns nil"
    (with-redefs [repo-dag/create-manager
                  (fn [] (throw (ex-info "dag boom" {})))]
      (let [output (with-out-str
                     (is (nil? (#'sut/create-repo-dag-manager))))]
        (is (.contains output "dag boom"))))))

;------------------------------------------------------------------------------ Layer 1
;; Dispatch table coverage

(def ^:private test-running-stale-threshold-ms 300000)

(def ^:private test-invalid-running-stale-threshold "invalid-threshold")

(def ^:private test-reconstructed-event-count 1)

(deftest dispatch-table-includes-pr-monitor-test
  (testing "pr monitor command is registered in dispatch table"
    (let [entries (filter #(= ["pr" "monitor"] (:cmds %)) sut/dispatch-table)]
      (is (= 1 (count entries)) "Exactly one pr monitor entry")
      (is (some? (:fn (first entries))) "Has a handler function")
      (is (= {:author {:alias :a} :poll-interval {:alias :p} :repo {}}
             (:spec (first entries)))
          "Spec includes --author, --poll-interval, and --repo"))))

(deftest workflow-status-summary-marks-quiet-running-checkpoints-stale-test
  (testing "running workflows with old last events are surfaced as stale"
    (let [now-ms (.toEpochMilli (java.time.Instant/parse "2026-05-17T00:16:00Z"))
          stale-ts "2026-05-17T00:10:59Z"]
      (with-redefs [app-config/events-dir (constantly "/tmp/events")
                    app-config/status-config
                    (constantly {:running-stale-threshold-ms test-running-stale-threshold-ms})
                    es/read-workflow-events-by-id
                    (fn [_events-dir _workflow-id]
                      [{:event/type :workflow/phase-completed
                        :event/timestamp stale-ts}])
                    wr/reconstruct-context
                    (fn [_events-dir _workflow-id]
                      {:completed? false
                       :failed? false
                       :dag-paused? false
                       :event-count test-reconstructed-event-count})
                    sut/current-time-ms (constantly now-ms)]
        (is (= :stale
               (:status (#'sut/workflow-status-summary "workflow-id"))))))))

(deftest workflow-status-summary-keeps-recent-running-checkpoints-running-test
  (testing "running workflows with recent events remain running"
    (let [now-ms (.toEpochMilli (java.time.Instant/parse "2026-05-17T00:16:00Z"))
          recent-ts "2026-05-17T00:12:00Z"]
      (with-redefs [app-config/events-dir (constantly "/tmp/events")
                    app-config/status-config
                    (constantly {:running-stale-threshold-ms test-running-stale-threshold-ms})
                    es/read-workflow-events-by-id
                    (fn [_events-dir _workflow-id]
                      [{:event/type :workflow/phase-completed
                        :event/timestamp recent-ts}])
                    wr/reconstruct-context
                    (fn [_events-dir _workflow-id]
                      {:completed? false
                       :failed? false
                       :dag-paused? false
                       :event-count test-reconstructed-event-count})
                    sut/current-time-ms (constantly now-ms)]
        (is (= :running
               (:status (#'sut/workflow-status-summary "workflow-id"))))))))

(deftest workflow-status-summary-falls-back-for-invalid-stale-threshold-test
  (testing "invalid stale threshold config falls back to the default threshold"
    (let [now-ms (.toEpochMilli (java.time.Instant/parse "2026-05-17T00:16:00Z"))
          stale-ts "2026-05-17T00:10:59Z"]
      (with-redefs [app-config/events-dir (constantly "/tmp/events")
                    app-config/status-config
                    (constantly {:running-stale-threshold-ms test-invalid-running-stale-threshold})
                    es/read-workflow-events-by-id
                    (fn [_events-dir _workflow-id]
                      [{:event/type :workflow/phase-completed
                        :event/timestamp stale-ts}])
                    wr/reconstruct-context
                    (fn [_events-dir _workflow-id]
                      {:completed? false
                       :failed? false
                       :dag-paused? false
                       :event-count test-reconstructed-event-count})
                    sut/current-time-ms (constantly now-ms)]
        (is (= :stale
               (:status (#'sut/workflow-status-summary "workflow-id"))))))))

;------------------------------------------------------------------------------ Layer 1
;; pr-monitor-cmd helpers

(def ^:private test-bounds {:min-poll-interval-s 5 :max-poll-interval-s 3600})

(deftest parse-poll-interval-test
  (testing "Valid interval returns milliseconds"
    (is (= 30000 (#'cmd-pr-monitor/parse-poll-interval "30" test-bounds))))
  (testing "Nil interval returns nil (domain default applies)"
    (is (nil? (#'cmd-pr-monitor/parse-poll-interval nil test-bounds))))
  (testing "Out-of-bounds interval returns nil with error message"
    (let [output (with-out-str
                   (is (nil? (#'cmd-pr-monitor/parse-poll-interval "1" test-bounds))))]
      (is (re-find #"5-3600" output))))
  (testing "Non-numeric interval returns nil with error message"
    (let [output (with-out-str
                   (is (nil? (#'cmd-pr-monitor/parse-poll-interval "abc" test-bounds))))]
      (is (re-find #"Invalid" output)))))
