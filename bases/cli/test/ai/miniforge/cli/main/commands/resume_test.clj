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

(ns ai.miniforge.cli.main.commands.resume-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [ai.miniforge.cli.main.display :as main-display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.cli.main.commands.resume :as sut]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.workflow-resume.interface :as wr]))

(deftest resolve-resume-workflow-test
  (testing "recorded workflow spec wins over configured fallback"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile]
                    (throw (ex-info "should not be called" {})))]
      (is (= {:workflow-type :financial-etl
              :workflow-version "1.2.3"}
             (sut/resolve-resume-workflow
              {:workflow-spec {:name "financial-etl"
                               :version "1.2.3"}})))))

  (testing "missing workflow spec falls back to app-configured default profile"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [profile]
                    (is (= :default profile))
                    :lean-sdlc-v1)]
      (is (= {:workflow-type :lean-sdlc-v1
              :workflow-version "latest"}
             (sut/resolve-resume-workflow {})))))

  (testing "missing configured fallback raises a clear error"
    (with-redefs [selection-config/resolve-selection-profile
                  (fn [_profile] nil)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Could not resolve a workflow type for resume"
           (sut/resolve-resume-workflow {}))))))

;------------------------------------------------------------------------------ Layer 1
;; read-event-file — bug fix coverage

(defn- with-temp-events-dir [body-fn]
  (let [base (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-resume-test-" (random-uuid)))
               .mkdirs)]
    (try
      (body-fn base)
      (finally
        (doseq [^java.io.File f (reverse (file-seq base))]
          (.delete f))))))

(defn- write-event! [^java.io.File dir filename event-map]
  (spit (io/file dir filename) (json/generate-string event-map)))

(deftest read-event-file-reads-per-event-json-from-workflow-dir-test
  ;; Regression guard for the iter-20 resume bug. Before this fix,
  ;; read-event-file looked for a single {workflow-id}.edn file that
  ;; was never written (the sink writes one .json per event to a dir),
  ;; so `mf run --resume <id>` always threw :anomalies/not-found.
  (with-temp-events-dir
    (fn [base-dir]
      (let [wf-id (str (random-uuid))
            wf-dir (doto (io/file base-dir wf-id) .mkdirs)]
        (write-event! wf-dir "20260420T000001Z-a.json"
                      {"~:event/type" "~:workflow/started"
                       "~:workflow/id" (str "~u" wf-id)})
        (write-event! wf-dir "20260420T000002Z-b.json"
                      {"~:event/type" "~:workflow/phase-completed"
                       "~:workflow/phase" "~:plan"
                       "~:phase/outcome" "~:success"})
        (testing "reads events in timestamp order with transit prefixes stripped"
          (with-redefs [sut/events-dir (.getPath base-dir)]
            (let [events (sut/read-event-file wf-id)]
              (is (= 2 (count events)))
              (is (= :workflow/started (:event/type (first events))))
              (is (= :workflow/phase-completed (:event/type (second events))))
              (is (= :plan (:workflow/phase (second events))))
              (is (= :success (:phase/outcome (second events)))))))))))

(deftest read-event-file-missing-workflow-returns-nil-test
  ;; The CLI wrapper now returns nil for missing workflows; the
  ;; user-facing :anomalies/not-found comes from the workflow-resume
  ;; component's `reconstruct-context` when callers use that path.
  (with-temp-events-dir
    (fn [base-dir]
      (with-redefs [sut/events-dir (.getPath base-dir)]
        (is (nil? (sut/read-event-file (str (random-uuid)))))))))

(deftest resume-workflow-passes-dag-recovery-data-test
  (let [workflow-id (random-uuid)
        run-pipeline-opts (atom nil)
        run-pipeline-workflow (atom nil)
        reconstructed {:completed-phases [:plan]
                       :event-count 0
                       :completed-dag-tasks #{:task-a}
                       :completed-dag-artifacts [{:artifact/id "art-1"}]
                       :workspace-checkpoint {:branch "task-a"
                                              :bundle-path "/tmp/task-a.bundle"}
                       :phase-results {:plan {:status :completed}}
                       :machine-snapshot {:execution/id workflow-id}
                       :workflow-spec {:name "canonical-sdlc"
                                       :version "1.0.0"}}]
    (with-redefs [wr/reconstruct-context (fn [_events-dir _workflow-id] reconstructed)
                  sut/resolve-resume-workflow (fn [_] {:workflow-type :canonical-sdlc
                                                       :workflow-version "1.0.0"})
                  context/create-llm-client (fn [_workflow _provider _quiet] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_event-stream] nil)
                  correlator/attach! (fn [_event-stream] nil)
                  dashboard/start-command-poller! (fn [_workflow-id _control-state]
                                                    (fn [] nil))
                  main-display/print-info (fn [& _] nil)
                  main-display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [_workflow-type _workflow-version _opts]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :verify}]}})
                  sut/run-pipeline (fn [workflow _input opts]
                                     (reset! run-pipeline-workflow workflow)
                                     (reset! run-pipeline-opts opts)
                                     {:execution/status :completed})]
      (let [result (sut/resume-workflow workflow-id {:quiet true})]
        (is (= :completed (:execution/status result)))
        (is (= [{:phase :verify}]
               (:workflow/pipeline @run-pipeline-workflow)))
        (is (nil? (:resume-reset-terminal? @run-pipeline-opts)))
        (is (= #{:task-a}
               (:pre-completed-dag-tasks @run-pipeline-opts)))
        (is (= [{:artifact/id "art-1"}]
               (:pre-completed-artifacts @run-pipeline-opts)))
        (is (= {:branch "task-a"
                :bundle-path "/tmp/task-a.bundle"}
               (:resume-workspace @run-pipeline-opts)))))))

(deftest terminal-status-predicate-test
  (testing "explicit terminal statuses are accepted"
    (doseq [s [:completed :completed-with-warnings :failed :aborted :cancelled]]
      (is (sut/terminal-status? s) (str s " must be terminal"))))
  (testing "non-terminal statuses are rejected"
    (doseq [s [:running :pending :paused nil :unknown :draining]]
      (is (not (sut/terminal-status? s)) (str s " must NOT be terminal")))))

(deftest resume-workflow-non-terminal-status-throws-test
  ;; Regression for the silent fast-fail blocker from the 2026-05-16
  ;; event-log-tool-visibility dogfood. Resume used to print
  ;; "Resumed workflow completed with status: :running" and exit 0,
  ;; losing the prior session's plan/explore/verify token spend.
  (let [workflow-id (random-uuid)
        reconstructed {:completed-phases [:plan]
                       :event-count 0
                       :machine-snapshot {:execution/id workflow-id
                                          :execution/current-phase :verify}
                       :workflow-spec {:name "canonical-sdlc" :version "1.0.0"}}]
    (with-redefs [wr/reconstruct-context (fn [_ _] reconstructed)
                  sut/resolve-resume-workflow (fn [_] {:workflow-type :canonical-sdlc
                                                       :workflow-version "1.0.0"})
                  context/create-llm-client (fn [_ _ _] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_] nil)
                  correlator/attach! (fn [_] nil)
                  dashboard/start-command-poller! (fn [_ _] (fn [] nil))
                  main-display/print-info (fn [& _] nil)
                  main-display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [& _]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :verify}]}})
                  sut/run-pipeline (fn [& _] {:execution/status :running})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"non-terminal status :running"
           (sut/resume-workflow workflow-id {:quiet true}))
          "Resume must throw, not silently return, when run-pipeline returns :running"))))

(deftest resume-print-phase-prefers-fsm-snapshot-test
  (testing "machine snapshot's :execution/current-phase wins over pipeline head"
    (is (= "verify"
           (#'sut/resume-print-phase
             {:execution/current-phase :verify}
             [{:phase :review} {:phase :release}]))))
  (testing "no snapshot → falls back to first remaining pipeline entry"
    (is (= "review"
           (#'sut/resume-print-phase nil [{:phase :review} {:phase :release}]))))
  (testing "snapshot present but :execution/current-phase missing → pipeline head"
    (is (= "review"
           (#'sut/resume-print-phase {} [{:phase :review}]))))
  (testing "both empty → nil"
    (is (nil? (#'sut/resume-print-phase nil [])))))

(deftest resume-workflow-trims-failed-checkpoint-before-running-test
  (let [workflow-id (random-uuid)
        run-pipeline-opts (atom nil)
        run-pipeline-workflow (atom nil)
        reconstructed {:completed-phases [:plan]
                       :event-count 0
                       :failed? true
                       :completed-dag-tasks #{}
                       :completed-dag-artifacts []
                       :phase-results {:plan {:status :completed}}
                       :machine-snapshot {:execution/id workflow-id
                                          :execution/status :failed
                                          :execution/fsm-state {:_state :failed}}
                       :workflow-spec {:name "canonical-sdlc"
                                       :version "1.0.0"}}]
    (with-redefs [wr/reconstruct-context (fn [_events-dir _workflow-id] reconstructed)
                  sut/resolve-resume-workflow (fn [_] {:workflow-type :canonical-sdlc
                                                       :workflow-version "1.0.0"})
                  context/create-llm-client (fn [_workflow _provider _quiet] :llm-client)
                  es/create-event-stream (fn [] :event-stream)
                  supervisory/attach! (fn [_event-stream] nil)
                  correlator/attach! (fn [_event-stream] nil)
                  dashboard/start-command-poller! (fn [_workflow-id _control-state]
                                                    (fn [] nil))
                  main-display/print-info (fn [& _] nil)
                  main-display/print-error (fn [& _] nil)
                  sut/load-workflow (fn [_workflow-type _workflow-version _opts]
                                      {:workflow {:workflow/id :canonical-sdlc
                                                  :workflow/version "1.0.0"
                                                  :workflow/pipeline [{:phase :plan}
                                                                      {:phase :implement}
                                                                      {:phase :verify}]}})
                  sut/run-pipeline (fn [workflow _input opts]
                                     (reset! run-pipeline-workflow workflow)
                                     (reset! run-pipeline-opts opts)
                                     {:execution/status :completed})]
      (let [result (sut/resume-workflow workflow-id {:quiet true})]
        (is (= :completed (:execution/status result)))
        (is (= [{:phase :implement} {:phase :verify}]
               (:workflow/pipeline @run-pipeline-workflow)))
        (is (true? (:resume-reset-terminal? @run-pipeline-opts)))
        (is (= workflow-id
               (get-in @run-pipeline-opts [:resume-machine-snapshot :execution/id])))))))
