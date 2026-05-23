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

(ns ai.miniforge.cli.workflow-runner.display-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
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

;------------------------------------------------------------------------------ extract-phase-summaries

(deftest extract-phase-summaries-from-execution-phase-results-test
  (testing "extracts from canonical :execution/phase-results"
    (let [result {:execution/phase-results
                  {:build {:status :success :metrics {:duration-ms 1200}}
                   :test  {:status :error   :metrics {:duration-ms 300}}}}]
      (is (= [{:phase :build :outcome :completed :duration-ms 1200}
              {:phase :test  :outcome :failure   :duration-ms 300}]
             (sut/extract-phase-summaries result))))))

(deftest extract-phase-summaries-nil-when-no-phases-test
  (testing "returns nil when no phase data present"
    (is (nil? (sut/extract-phase-summaries {:execution/status :completed})))
    (is (nil? (sut/extract-phase-summaries {:phases [{:phase :legacy}]})))
    (is (nil? (sut/extract-phase-summaries {})))))

(deftest extract-phase-summaries-nil-on-non-map-test
  (testing "returns nil for non-map input"
    (is (nil? (sut/extract-phase-summaries nil)))
    (is (nil? (sut/extract-phase-summaries [1 2 3])))))

;------------------------------------------------------------------------------ extract-failed-tasks

(deftest extract-failed-tasks-from-canonical-dag-result-test
  (testing "extracts from canonical :execution/dag-result"
    (is (= ["task-a" "task-b"]
           (sut/extract-failed-tasks
            {:execution/dag-result {:failed-task-ids [:task-a "task-b"]}})))))

(deftest extract-failed-tasks-nil-when-none-test
  (testing "returns nil when no failed tasks"
    (is (nil? (sut/extract-failed-tasks {:failed-task-ids ["legacy"]})))
    (is (nil? (sut/extract-failed-tasks {})))))

;------------------------------------------------------------------------------ extract-pr-urls

(deftest extract-pr-urls-from-workflow-pr-info-test
  (testing "extracts from the release phase PR info"
    (is (= ["https://github.com/org/repo/pull/42"]
           (sut/extract-pr-urls
            {:workflow/pr-info {:pr-url "https://github.com/org/repo/pull/42"}})))))

(deftest extract-pr-urls-from-dag-pr-infos-test
  (testing "extracts URL values from DAG PR info"
    (let [result {:execution/dag-pr-infos [{:pr-url "https://github.com/org/repo/pull/1"}
                                           {:pr/url "https://github.com/org/repo/pull/2"}]}]
      (is (= ["https://github.com/org/repo/pull/1"
              "https://github.com/org/repo/pull/2"]
             (sut/extract-pr-urls result))))))

(deftest extract-pr-urls-nil-when-none-test
  (testing "returns nil when no PR URLs present"
    (is (nil? (sut/extract-pr-urls {:pr/url "https://github.com/org/repo/pull/42"})))
    (is (nil? (sut/extract-pr-urls {:execution/status :completed})))
    (is (nil? (sut/extract-pr-urls {})))))

;------------------------------------------------------------------------------ format-compact-summary

(deftest format-compact-summary-success-contains-status-test
  (testing "compact summary for a successful run includes success marker"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [result {:execution/status :completed
                    :execution/metrics {:tokens 100 :cost-usd 0.001 :duration-ms 5000}}
            s (sut/format-compact-summary result)]
        (is (string? s))
        (is (str/includes? s (messages/t :workflow-runner/summary-success)))))))

(deftest format-compact-summary-failure-contains-errors-test
  (testing "compact summary for a failed run includes errors"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [result {:execution/status :failed
                    :execution/errors ["gate lint failed"]}
            s (sut/format-compact-summary result)]
        (is (str/includes? s "gate lint failed"))))))

(deftest format-compact-summary-includes-phase-lines-test
  (testing "compact summary includes one line per phase when phases present"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [result {:execution/status :completed
                    :execution/phase-results
                    {:build {:status :success :metrics {:duration-ms 2000}}
                     :test  {:status :error   :metrics {:duration-ms 500}}}}
            s (sut/format-compact-summary result)]
        (is (str/includes? s "build"))
        (is (str/includes? s "test"))))))

(deftest format-compact-summary-includes-failed-tasks-test
  (testing "compact summary includes failed task IDs when present"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [result {:execution/status :failed
                    :execution/dag-result {:failed-task-ids ["task-x" "task-y"]}}
            s (sut/format-compact-summary result)]
        (is (str/includes? s "task-x"))
        (is (str/includes? s "task-y"))))))

(deftest format-compact-summary-includes-pr-urls-test
  (testing "compact summary includes PR URLs when present"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [result {:execution/status :completed
                    :workflow/pr-info {:pr-url "https://github.com/org/repo/pull/10"}}
            s (sut/format-compact-summary result)]
        (is (str/includes? s "https://github.com/org/repo/pull/10"))))))

(deftest format-compact-summary-includes-events-pointer-test
  (testing "compact summary includes events directory pointer"
    (with-redefs [app-config/events-dir (constantly "/my/events/dir")]
      (let [result {:execution/status :completed}
            s (sut/format-compact-summary result)]
        (is (str/includes? s "/my/events/dir"))))))

(deftest format-compact-summary-includes-full-hint-test
  (testing "compact summary includes --output edn hint"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [s (sut/format-compact-summary {:execution/status :completed})]
        (is (str/includes? s "edn"))))))

;------------------------------------------------------------------------------ print-pretty-result

(deftest print-pretty-result-no-pprint-test
  (testing "print-pretty-result does not dump raw EDN pprint"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [result {:execution/status :completed
                    :execution/metrics {:tokens 42 :cost-usd 0.0 :duration-ms 1000}
                    :secret-key "should-not-appear-via-pprint"}
            out (with-out-str (sut/print-pretty-result result))]
        ;; Should NOT contain raw pprint of the map (no :secret-key label)
        (is (not (str/includes? out ":secret-key")))
        ;; Should contain the compact success marker instead
        (is (str/includes? out (messages/t :workflow-runner/summary-success)))))))

;------------------------------------------------------------------------------ print-result dispatch

(deftest print-result-edn-branch-pprints-test
  (testing ":edn output produces a clojure.pprint dump"
    (let [result {:execution/status :completed :data 99}
          out (with-out-str (sut/print-result result {:output :edn :quiet false}))]
      ;; pprint includes the map keys
      (is (str/includes? out ":data")))))

(deftest print-result-json-branch-test
  (testing ":json output produces JSON"
    (let [result {:execution/status :completed}
          out (with-out-str (sut/print-result result {:output :json :quiet false}))]
      (is (str/includes? out "{")))))

(deftest print-result-pretty-branch-test
  (testing ":pretty output calls compact summary"
    (with-redefs [app-config/events-dir (constantly "/tmp/events")]
      (let [result {:execution/status :completed}
            out (with-out-str (sut/print-result result {:output :pretty :quiet false}))]
        (is (str/includes? out (messages/t :workflow-runner/summary-success)))))))

(deftest print-result-else-branch-pprints-test
  (testing "unknown output keyword falls back to pprint"
    (let [result {:execution/status :completed :mystery-key true}
          out (with-out-str (sut/print-result result {:output :unknown-format :quiet false}))]
      (is (str/includes? out ":mystery-key")))))
