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

(ns ai.miniforge.cli.main.commands.workflow-commands-test
  "Unit tests for workflow subcommands: execute, status, cancel."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [babashka.fs :as fs]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.commands.workflow-commands :as sut]))

;------------------------------------------------------------------------------ Layer 0: Fixtures & factories

(def ^:dynamic *tmp-dir* nil)

(defn tmp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "workflow-cmd-test-"}))]
    (binding [*tmp-dir* dir]
      (try
        (f)
        (finally
          (fs/delete-tree dir))))))

(use-fixtures :each tmp-dir-fixture)

(defn make-events
  "Build a sequence of workflow events for testing."
  ([]
   (make-events :completed))
  ([final-state]
   (let [base [{:event/type :workflow/started
                :event/timestamp "2026-04-13T10:00:00Z"}
               {:event/type :workflow/phase-completed
                :workflow/phase :plan
                :phase/outcome :success
                :phase/duration-ms 5000}]]
     (case final-state
       :completed (conj base {:event/type :workflow/completed})
       :failed    (conj base {:event/type :workflow/failed
                              :workflow/failure-reason "test failure"})
       :running   base
       base))))

;------------------------------------------------------------------------------ Layer 1: Tests

(deftest derive-status-test
  (testing "completed events derive 'completed' status"
    (is (= "completed" (sut/derive-status (make-events :completed)))))

  (testing "failed events derive 'failed' status"
    (is (= "failed" (sut/derive-status (make-events :failed)))))

  (testing "only started events derive 'running' status"
    (is (= "running" (sut/derive-status (make-events :running)))))

  (testing "empty events derive 'unknown' status"
    (is (= "unknown" (sut/derive-status [])))))

(deftest workflow-execute-cmd-missing-spec-test
  (testing "execute command exits with error when no spec provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/workflow-execute-cmd {}))
        (is @exited?)))))

(deftest workflow-status-cmd-missing-id-test
  (testing "status command exits with error when no id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/workflow-status-cmd {}))
        (is @exited?)))))

(deftest workflow-status-cmd-not-found-test
  (testing "status command shows error when workflow not found"
    (let [exited? (atom false)]
      (with-redefs [app-config/events-dir (constantly *tmp-dir*)
                    shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/workflow-status-cmd {:id "missing-wf"}))
        (is @exited?)))))

(deftest workflow-status-cmd-shows-status-test
  (testing "status command displays workflow status from event file"
    (let [events-dir *tmp-dir*
          events (make-events :completed)
          event-file (str events-dir "/wf-123.edn")]
      (spit event-file (apply str (map #(str (pr-str %) "\n") events)))
      (with-redefs [app-config/events-dir (constantly events-dir)]
        (let [output (with-out-str (sut/workflow-status-cmd {:id "wf-123"}))]
          (is (.contains output "completed")))))))

(deftest workflow-cancel-cmd-missing-id-test
  (testing "cancel command exits with error when no id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/workflow-cancel-cmd {}))
        (is @exited?)))))

(deftest workflow-cancel-cmd-writes-cancel-file-test
  (testing "cancel command writes a cancel signal file"
    (let [commands-dir (str *tmp-dir* "/commands")]
      (with-redefs [app-config/commands-dir (constantly commands-dir)]
        (let [output (with-out-str (sut/workflow-cancel-cmd {:id "wf-456"}))]
          (is (.contains output "Cancel"))
          (is (fs/exists? commands-dir))
          (let [files (fs/list-dir commands-dir)]
            (is (pos? (count files)))))))))
