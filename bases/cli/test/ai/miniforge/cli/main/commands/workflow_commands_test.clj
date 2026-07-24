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
  "Unit tests for workflow subcommands: execute, status, cancel, gc-scratch."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [babashka.fs :as fs]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.commands.workflow-commands :as sut]
   [ai.miniforge.cli.worktree :as worktree]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as gc-queue]
   [ai.miniforge.event-stream.interface :as es]))

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

(deftest workflow-cancel-cmd-requests-a-cancel-intervention-test
  (testing "cancel command writes a governed intervention request"
    (let [requests (atom [])]
      (with-redefs [es/request-intervention!
                    (fn [request]
                      (swap! requests conj request)
                      (assoc request :intervention/id (random-uuid)))]
        (let [output (with-out-str (sut/workflow-cancel-cmd {:id "wf-456"}))
              request (first @requests)]
          (is (.contains output "Cancel"))
          (is (= 1 (count @requests)))
          ;; The legacy poller verb was `stop`; the intervention
          ;; vocabulary names the same operation `:cancel`.
          (is (= :cancel (:intervention/type request)))
          (is (= :workflow (:intervention/target-type request)))
          (is (= "wf-456" (:intervention/target-id request)))
          (is (= :cli (:intervention/request-source request)))
          (is (string? (:intervention/requested-by request))))))))

(deftest workflow-cancel-cmd-reports-write-failure-test
  (testing "a failed request is reported, never reported as success"
    (with-redefs [es/request-intervention!
                  (fn [_request] (throw (ex-info "disk full" {})))]
      (let [output (with-out-str (sut/workflow-cancel-cmd {:id "wf-789"}))]
        (is (.contains output "disk full"))
        (is (not (.contains output "Cancel signal sent")))))))

(deftest workflow-cancel-cmd-reports-anomaly-test
  (testing "an :invalid-input anomaly from the writer surfaces as an error"
    (with-redefs [es/request-intervention!
                  (fn [_request]
                    (anomaly/anomaly :invalid-input "bad request" {}))]
      (let [output (with-out-str (sut/workflow-cancel-cmd {:id "wf-789"}))]
        (is (.contains output "bad request"))
        (is (not (.contains output "Cancel signal sent")))))))

;------------------------------------------------------------------------------ gc-scratch-cmd tests
;; All git operations are mocked — no shell subprocesses spawned.

(deftest workflow-gc-scratch-cmd-no-repo-test
  (testing "exits with code 1 and prints an error when not inside a git repo"
    (let [exited? (atom false)
          output  (atom "")]
      (with-redefs [worktree/worktree-root (constantly nil)
                    shared/exit! (fn [_] (reset! exited? true))]
        (reset! output (with-out-str (sut/workflow-gc-scratch-cmd {}))))
      (is @exited?)
      ;; The i18n message must mention "git repository" so the user understands.
      (is (re-find #"(?i)git" @output)))))

(deftest workflow-gc-scratch-cmd-success-test
  (testing "prints a summary line on successful GC"
    (let [output  (atom "")
          gc-data {:pruned 2 :remaining 1
                   :gc-result {:deleted ["refs/miniforge/scratch/wf-a"
                                         "refs/miniforge/scratch/wf-b"]
                               :retained 0}}]
      (with-redefs [worktree/worktree-root   (constantly "/fake/repo")
                    gc-queue/run-deferred-gc! (fn [_ _] (gc-queue/ok gc-data))]
        (reset! output (with-out-str (sut/workflow-gc-scratch-cmd {:max-age-days 7}))))
      ;; Output must contain the pruned count and a GC-related keyword.
      (is (re-find #"2" @output))
      (is (re-find #"(?i)pruned|GC|scratch" @output)))))

(deftest workflow-gc-scratch-cmd-gc-failure-test
  (testing "exits with code 1 when gc-queue/run-deferred-gc! returns an error"
    (let [exited? (atom false)]
      (with-redefs [worktree/worktree-root   (constantly "/fake/repo")
                    gc-queue/run-deferred-gc! (fn [_ _]
                                                (gc-queue/err :gc/test-error
                                                              "simulated GC failure"
                                                              {}))
                    shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/workflow-gc-scratch-cmd {})))
      (is @exited?))))

(deftest workflow-gc-scratch-cmd-uses-default-max-age-test
  (testing "passes gc-default-max-age-days when no :max-age-days option given"
    (let [captured-age (atom nil)]
      (with-redefs [worktree/worktree-root   (constantly "/fake/repo")
                    gc-queue/run-deferred-gc! (fn [_ age]
                                                (reset! captured-age age)
                                                (gc-queue/ok {:pruned 0 :remaining 0 :gc-result nil}))]
        (with-out-str (sut/workflow-gc-scratch-cmd {})))
      (is (= gc-queue/gc-default-max-age-days @captured-age)))))
