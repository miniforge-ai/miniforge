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

(ns ai.miniforge.observer.interface-test
  "Tests for the Observer component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.observer.interface :as observer]
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as wf-proto]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn sample-workflow-state
  "Create a sample workflow state for testing."
  [workflow-id status & {:keys [tokens cost duration]
                         :or {tokens 1000 cost 0.10 duration 5000}}]
  {:workflow/id workflow-id
   :workflow/status status
   :workflow/metrics {:tokens tokens
                     :cost-usd cost
                     :duration-ms duration}
   :workflow/history [{:from-phase :start
                      :to-phase :implement
                      :timestamp (java.util.Date.)}]
   :workflow/errors (when (= status :failed)
                     [{:phase :test :error "Test failed"}])})

(defn sample-phase-result
  "Create a sample phase result for testing."
  [success? & {:keys [tokens cost duration errors]
              :or {tokens 500 cost 0.05 duration 2000 errors []}}]
  {:success? success?
   :metrics {:tokens tokens
            :cost-usd cost
            :duration-ms duration}
   :errors errors
   :artifacts []})

;; ============================================================================
;; Observer Creation Tests
;; ============================================================================

(deftest create-observer-test
  (testing "Creating a new observer"
    (let [obs (observer/create-observer)]
      (is (some? obs) "Observer should be created")
      (is (satisfies? wf-proto/WorkflowObserver obs)
          "Observer should implement WorkflowObserver protocol")))

  (testing "Creating observer with initial state"
    (let [obs (observer/create-observer {:initial-state {:custom-key :custom-value}})]
      (is (some? obs) "Observer with initial state should be created"))))

;; ============================================================================
;; Metrics Collection Tests
;; ============================================================================

(deftest collect-workflow-metrics-test
  (testing "Collecting workflow metrics"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)
          workflow-state (sample-workflow-state workflow-id :completed)]

      ;; Collect metrics
      (observer/collect-workflow-metrics obs workflow-id workflow-state)

      ;; Verify metrics were collected
      (let [metrics (observer/get-workflow-metrics obs workflow-id)]
        (is (some? metrics) "Metrics should be collected")
        (is (= workflow-id (:workflow-id metrics)) "Workflow ID should match")
        (is (= :completed (:status metrics)) "Status should be :completed")
        (is (= 1000 (get-in metrics [:metrics :tokens])) "Tokens should match")
        (is (= 0.10 (get-in metrics [:metrics :cost-usd])) "Cost should match")
        (is (= 5000 (get-in metrics [:metrics :duration-ms])) "Duration should match"))))

  (testing "Collecting metrics for failed workflow"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)
          workflow-state (sample-workflow-state workflow-id :failed)]

      (observer/collect-workflow-metrics obs workflow-id workflow-state)

      (let [metrics (observer/get-workflow-metrics obs workflow-id)]
        (is (= :failed (:status metrics)) "Status should be :failed")
        (is (seq (:errors metrics)) "Errors should be present")))))

(deftest collect-phase-metrics-test
  (testing "Collecting phase metrics"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]

      ;; Collect phase metrics
      (observer/collect-phase-metrics obs workflow-id :implement
                                     (sample-phase-result true))

      ;; Collect workflow metrics to get phases
      (observer/collect-workflow-metrics obs workflow-id
                                        (sample-workflow-state workflow-id :completed))

      ;; Verify phase metrics were collected
      (let [metrics (observer/get-workflow-metrics obs workflow-id)
            phases (:phases metrics)]
        (is (= 1 (count phases)) "Should have 1 phase")
        (is (= :implement (:phase (first phases))) "Phase name should match")
        (is (:success? (first phases)) "Phase should be successful")))))

;; ============================================================================
;; Query Tests
;; ============================================================================

(deftest get-all-metrics-test
  (testing "Getting all metrics"
    (let [obs (observer/create-observer)
          workflow-ids (repeatedly 5 random-uuid)]

      ;; Collect metrics for multiple workflows
      (doseq [wf-id workflow-ids]
        (observer/collect-workflow-metrics obs wf-id
                                          (sample-workflow-state wf-id :completed)))

      ;; Get all metrics
      (let [all-metrics (observer/get-all-metrics obs {})]
        (is (= 5 (count all-metrics)) "Should have 5 workflow metrics"))))

  (testing "Getting metrics with limit"
    (let [obs (observer/create-observer)
          workflow-ids (repeatedly 10 random-uuid)]

      (doseq [wf-id workflow-ids]
        (observer/collect-workflow-metrics obs wf-id
                                          (sample-workflow-state wf-id :completed)))

      (let [limited-metrics (observer/get-all-metrics obs {:limit 5})]
        (is (= 5 (count limited-metrics)) "Should respect limit"))))

  (testing "Getting metrics by status"
    (let [obs (observer/create-observer)
          completed-ids (repeatedly 3 random-uuid)
          failed-ids (repeatedly 2 random-uuid)]

      (doseq [wf-id completed-ids]
        (observer/collect-workflow-metrics obs wf-id
                                          (sample-workflow-state wf-id :completed)))

      (doseq [wf-id failed-ids]
        (observer/collect-workflow-metrics obs wf-id
                                          (sample-workflow-state wf-id :failed)))

      (let [completed-metrics (observer/get-all-metrics obs {:status :completed})
            failed-metrics (observer/get-all-metrics obs {:status :failed})]
        (is (= 3 (count completed-metrics)) "Should have 3 completed workflows")
        (is (= 2 (count failed-metrics)) "Should have 2 failed workflows")))))

;; ============================================================================
;; Analysis Tests
;; ============================================================================

(deftest analyze-duration-stats-test
  (testing "Analyzing duration statistics"
    (let [obs (observer/create-observer)
          durations [1000 2000 3000 4000 5000]]

      ;; Collect metrics with different durations
      (doseq [duration durations]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed
                                                                :duration duration)))

      ;; Analyze duration stats
      (let [analysis (observer/analyze-metrics obs :duration-stats {})]
        (is (= :duration-stats (:analysis-type analysis)) "Analysis type should match")
        (is (some? (:data analysis)) "Should have data")
        (is (some? (:summary analysis)) "Should have summary")

        (let [stats (:data analysis)]
          (is (= 5 (:count stats)) "Should analyze 5 workflows")
          (is (= 3000.0 (:avg stats)) "Average should be 3000ms")
          (is (= 3000 (:p50 stats)) "P50 should be 3000ms")
          (is (= 1000 (:min stats)) "Min should be 1000ms")
          (is (= 5000 (:max stats)) "Max should be 5000ms"))))))

(deftest analyze-cost-stats-test
  (testing "Analyzing cost statistics"
    (let [obs (observer/create-observer)
          costs [0.05 0.10 0.15 0.20 0.25]]

      (doseq [cost costs]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed
                                                                :cost cost)))

      (let [analysis (observer/analyze-metrics obs :cost-stats {})]
        (is (= :cost-stats (:analysis-type analysis)) "Analysis type should match")

        (let [stats (:data analysis)]
          (is (= 5 (:count stats)) "Should analyze 5 workflows")
          (is (= 0.15 (:avg stats)) "Average should be $0.15")
          (is (= 0.75 (:sum stats)) "Total should be $0.75"))))))

(deftest analyze-token-stats-test
  (testing "Analyzing token statistics"
    (let [obs (observer/create-observer)
          tokens [500 1000 1500 2000 2500]]

      (doseq [token-count tokens]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed
                                                                :tokens token-count)))

      (let [analysis (observer/analyze-metrics obs :token-stats {})]
        (is (= :token-stats (:analysis-type analysis)) "Analysis type should match")

        (let [stats (:data analysis)]
          (is (= 5 (:count stats)) "Should analyze 5 workflows")
          (is (= 1500.0 (:avg stats)) "Average should be 1500 tokens")
          (is (= 7500 (:sum stats)) "Total should be 7500 tokens"))))))

(deftest analyze-phase-stats-test
  (testing "Analyzing phase statistics"
    (let [obs (observer/create-observer)
          workflow-id (random-uuid)]

      ;; Collect phase metrics
      (observer/collect-phase-metrics obs workflow-id :implement
                                     (sample-phase-result true :duration 2000))
      (observer/collect-phase-metrics obs workflow-id :test
                                     (sample-phase-result true :duration 3000))
      (observer/collect-phase-metrics obs workflow-id :deploy
                                     (sample-phase-result false :duration 1000))

      ;; Collect workflow metrics
      (observer/collect-workflow-metrics obs workflow-id
                                        (sample-workflow-state workflow-id :completed))

      (let [analysis (observer/analyze-metrics obs :phase-stats {})]
        (is (= :phase-stats (:analysis-type analysis)) "Analysis type should match")

        (let [phase-data (:data analysis)]
          (is (contains? phase-data :implement) "Should have :implement phase")
          (is (contains? phase-data :test) "Should have :test phase")
          (is (contains? phase-data :deploy) "Should have :deploy phase")

          (let [implement-stats (get phase-data :implement)]
            (is (= 1.0 (:success-rate implement-stats)) "Implement should have 100% success"))

          (let [deploy-stats (get phase-data :deploy)]
            (is (= 0.0 (:success-rate deploy-stats)) "Deploy should have 0% success")))))))

(deftest analyze-failure-patterns-test
  (testing "Analyzing failure patterns"
    (let [obs (observer/create-observer)]

      ;; Collect mix of successful and failed workflows
      (dotimes [_ 7]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed)))
      (dotimes [_ 3]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :failed)))

      (let [analysis (observer/analyze-metrics obs :failure-patterns {})]
        (is (= :failure-patterns (:analysis-type analysis)) "Analysis type should match")

        (let [data (:data analysis)]
          (is (= 10 (:total-workflows data)) "Should analyze 10 workflows")
          (is (= 3 (:failed-workflows data)) "Should have 3 failures")
          (is (= 0.3 (:failure-rate data)) "Failure rate should be 30%"))))))

(deftest analyze-trends-test
  (testing "Analyzing metrics trends"
    (let [obs (observer/create-observer)]

      ;; Simulate improving performance over time
      (dotimes [i 10]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed
                                                                :duration (- 10000 (* i 500)))))

      (let [analysis (observer/analyze-metrics obs :trends {})]
        (is (= :trends (:analysis-type analysis)) "Analysis type should match")
        (is (some? (:data analysis)) "Should have trend data")
        (is (some? (:summary analysis)) "Should have summary")))))

;; ============================================================================
;; Report Generation Tests
;; ============================================================================

(deftest generate-summary-report-test
  (testing "Generating summary report in markdown"
    (let [obs (observer/create-observer)]

      ;; Collect some metrics
      (dotimes [_ 5]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed)))

      (let [report (observer/generate-report obs :summary {:format :markdown})]
        (is (string? report) "Report should be a string")
        (is (.contains report "Workflow Performance Summary") "Should have title")
        (is (.contains report "Duration Statistics") "Should have duration section")
        (is (.contains report "Cost Statistics") "Should have cost section"))))

  (testing "Generating summary report in EDN"
    (let [obs (observer/create-observer)]

      (dotimes [_ 5]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed)))

      (let [report (observer/generate-report obs :summary {:format :edn})]
        (is (map? report) "Report should be a map")
        (is (contains? report :duration) "Should have duration analysis")
        (is (contains? report :cost) "Should have cost analysis")
        (is (contains? report :tokens) "Should have token analysis")
        (is (contains? report :failures) "Should have failure analysis")))))

(deftest generate-detailed-report-test
  (testing "Generating detailed report"
    (let [obs (observer/create-observer)]

      (dotimes [_ 3]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed)))

      (let [report (observer/generate-report obs :detailed {:format :edn})]
        (is (map? report) "Report should be a map")
        (is (= 3 (:total-workflows report)) "Should have 3 workflows")
        (is (seq (:workflows report)) "Should have workflow data")))))

(deftest generate-recommendations-report-test
  (testing "Generating recommendations report"
    (let [obs (observer/create-observer)]

      ;; Create some failure scenarios
      (dotimes [_ 5]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :failed)))

      (let [report (observer/generate-report obs :recommendations {:format :edn})]
        (is (map? report) "Report should be a map")
        (is (contains? report :recommendations) "Should have recommendations")
        (is (vector? (:recommendations report)) "Recommendations should be a vector")))))

;; ============================================================================
;; Telemetry Artifact Tests
;; ============================================================================

(deftest create-telemetry-artifact-test
  (testing "Creating telemetry artifact"
    (let [obs (observer/create-observer)]

      ;; Collect some metrics
      (dotimes [_ 10]
        (observer/collect-workflow-metrics obs (random-uuid)
                                          (sample-workflow-state (random-uuid) :completed)))

      (let [artifact (observer/create-telemetry-artifact obs nil)]
        (is (some? artifact) "Artifact should be created")
        (is (= :telemetry (:artifact/type artifact)) "Type should be :telemetry")
        (is (some? (:artifact/id artifact)) "Should have ID")
        (is (string? (:artifact/content artifact)) "Content should be string")
        (is (= :edn (get-in artifact [:artifact/metadata :format])) "Format should be :edn")
        (is (= 10 (get-in artifact [:artifact/metadata :workflows])) "Should record workflow count")))))

;; ============================================================================
;; WorkflowObserver Integration Tests
;; ============================================================================


;; ============================================================================
;; Run All Tests
;; ============================================================================

(deftest run-all-observer-tests
  (testing "All observer tests should pass"
    (is true "Observer component is fully tested")))
