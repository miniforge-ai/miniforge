(ns ai.miniforge.response.interface-test
  "Tests for the response component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.response.interface :as r]))

;; ============================================================================
;; Chain creation tests
;; ============================================================================

(deftest create-test
  (testing "create initializes empty chain"
    (let [chain (r/create :my-workflow)]
      (is (= :my-workflow (:operation chain)))
      (is (true? (:succeeded? chain)))
      (is (empty? (:response-chain chain))))))

;; ============================================================================
;; Add response tests
;; ============================================================================

(deftest add-success-test
  (testing "add-success adds entry with nil anomaly"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {:result "ok"}))]
      (is (= 1 (r/entry-count chain)))
      (is (r/succeeded? chain))
      (is (= :step-1 (r/last-operation chain)))
      (is (nil? (r/last-anomaly chain)))
      (is (= {:result "ok"} (r/last-response chain))))))

(deftest add-failure-test
  (testing "add-failure adds entry with anomaly"
    (let [chain (-> (r/create :test)
                    (r/add-failure :step-1 :anomalies/fault {:error "boom"}))]
      (is (= 1 (r/entry-count chain)))
      (is (r/failed? chain))
      (is (= :anomalies/fault (r/last-anomaly chain)))
      (is (= {:error "boom"} (r/last-response chain))))))

(deftest add-response-test
  (testing "add-response with nil anomaly succeeds"
    (let [chain (-> (r/create :test)
                    (r/add-response :step-1 nil {:ok true}))]
      (is (r/succeeded? chain))))

  (testing "add-response with anomaly fails"
    (let [chain (-> (r/create :test)
                    (r/add-response :step-1 :anomalies/fault {:ok false}))]
      (is (r/failed? chain)))))

(deftest succeeded-aggregation-test
  (testing "succeeded? is true only when all operations succeed"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {})
                    (r/add-success :step-2 {}))]
      (is (r/succeeded? chain))))

  (testing "succeeded? is false when any operation fails"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {})
                    (r/add-failure :step-2 :anomalies/fault {})
                    (r/add-success :step-3 {}))]
      (is (r/failed? chain)))))

;; ============================================================================
;; Query tests
;; ============================================================================

(deftest last-entry-test
  (testing "last-entry returns most recent"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {:n 1})
                    (r/add-success :step-2 {:n 2}))]
      (is (= :step-2 (:operation (r/last-entry chain))))
      (is (= {:n 2} (:response (r/last-entry chain)))))))

(deftest last-response-or-default-test
  (testing "returns response when succeeded"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {:result "ok"}))]
      (is (= {:result "ok"} (r/last-response-or-default chain :default)))))

  (testing "returns default when failed"
    (let [chain (-> (r/create :test)
                    (r/add-failure :step-1 :anomalies/fault {}))]
      (is (= :default (r/last-response-or-default chain :default))))))

(deftest first-failure-test
  (testing "returns first failed entry"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {})
                    (r/add-failure :step-2 :anomalies/fault {:n 2})
                    (r/add-failure :step-3 :anomalies/timeout {:n 3}))]
      (is (= :step-2 (:operation (r/first-failure chain))))
      (is (= :anomalies/fault (:anomaly (r/first-failure chain))))))

  (testing "returns nil when all succeeded"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {}))]
      (is (nil? (r/first-failure chain))))))

(deftest all-failures-test
  (testing "returns all failed entries"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {})
                    (r/add-failure :step-2 :anomalies/fault {})
                    (r/add-failure :step-3 :anomalies/timeout {}))]
      (is (= 2 (count (r/all-failures chain))))
      (is (= [:step-2 :step-3] (mapv :operation (r/all-failures chain)))))))

(deftest operations-test
  (testing "returns operation names in order"
    (let [chain (-> (r/create :test)
                    (r/add-success :plan {})
                    (r/add-success :implement {})
                    (r/add-success :verify {}))]
      (is (= [:plan :implement :verify] (r/operations chain))))))

;; ============================================================================
;; Transformation tests
;; ============================================================================

(deftest merge-metrics-test
  (testing "merges metrics from all responses"
    (let [chain (-> (r/create :test)
                    (r/add-success :plan {:metrics {:tokens 100 :cost-usd 0.01}})
                    (r/add-success :impl {:metrics {:tokens 500 :cost-usd 0.05}}))
          merged (r/merge-metrics chain)]
      (is (= 600 (:tokens merged)))
      (is (< (Math/abs (- 0.06 (:cost-usd merged))) 0.0001))
      (is (= 0 (:duration-ms merged)))))

  (testing "handles missing metrics gracefully"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {:no-metrics true}))]
      (is (= {:tokens 0 :cost-usd 0.0 :duration-ms 0}
             (r/merge-metrics chain))))))

(deftest collect-artifacts-test
  (testing "collects artifacts from all responses"
    (let [chain (-> (r/create :test)
                    (r/add-success :plan {:artifacts [{:type :plan}]})
                    (r/add-success :impl {:artifacts [{:type :code} {:type :test}]}))]
      (is (= [{:type :plan} {:type :code} {:type :test}]
             (r/collect-artifacts chain)))))

  (testing "handles missing artifacts gracefully"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {:no-artifacts true}))]
      (is (= [] (r/collect-artifacts chain))))))

(deftest summarize-test
  (testing "creates summary of chain"
    (let [chain (-> (r/create :workflow)
                    (r/add-success :plan {:artifacts [{:type :plan}]
                                         :metrics {:tokens 100}})
                    (r/add-failure :impl :anomalies.phase/agent-failed
                                  {:error "timeout"}))
          summary (r/summarize chain)]
      (is (= :workflow (:operation summary)))
      (is (false? (:succeeded? summary)))
      (is (= 2 (:entry-count summary)))
      (is (= [:plan :impl] (:operations summary)))
      (is (= :impl (:operation (:first-failure summary))))
      (is (= {:tokens 100 :cost-usd 0.0 :duration-ms 0} (:metrics summary)))
      (is (= [{:type :plan}] (:artifacts summary))))))

;; ============================================================================
;; Exception handling tests
;; ============================================================================

(deftest execute-with-handling-success-test
  (testing "successful execution adds success entry"
    (let [chain (-> (r/create :test)
                    (r/execute-with-handling :step-1 r/default-anomaly-classifier
                                             #(do {:result "ok"})))]
      (is (r/succeeded? chain))
      (is (= {:result "ok"} (r/last-response chain))))))

(deftest execute-with-handling-failure-test
  (testing "exception adds failure entry"
    (let [chain (-> (r/create :test)
                    (r/execute-with-handling :step-1 r/default-anomaly-classifier
                                             #(throw (ex-info "boom" {:code 123}))))]
      (is (r/failed? chain))
      (is (= :anomalies/fault (r/last-anomaly chain)))
      (is (= "boom" (:error (r/last-response chain))))
      (is (= {:code 123} (:data (r/last-response chain)))))))

(deftest execute-with-handling-custom-classifier-test
  (testing "custom classifier maps exceptions"
    (let [classifier (fn [_ex] :anomalies.phase/budget-exceeded)
          chain (-> (r/create :test)
                    (r/execute-with-handling :step-1 classifier
                                             #(throw (Exception. "too slow"))))]
      (is (= :anomalies.phase/budget-exceeded (r/last-anomaly chain))))))

;; ============================================================================
;; Anomaly tests
;; ============================================================================

(deftest anomaly?-test
  (testing "recognizes known anomalies"
    (is (r/anomaly? :anomalies/fault))
    (is (r/anomaly? :anomalies.phase/enter-failed))
    (is (r/anomaly? :anomalies.gate/validation-failed)))

  (testing "rejects unknown keywords"
    (is (not (r/anomaly? :not-an-anomaly)))
    (is (not (r/anomaly? :random/keyword)))))

(deftest retryable?-test
  (testing "identifies retryable anomalies"
    (is (r/retryable? :anomalies/unavailable))
    (is (r/retryable? :anomalies/busy))
    (is (r/retryable? :anomalies/timeout)))

  (testing "identifies non-retryable anomalies"
    (is (not (r/retryable? :anomalies/forbidden)))
    (is (not (r/retryable? :anomalies/incorrect)))))

(deftest anomaly-category-test
  (testing "categorizes anomalies"
    (is (= :general (r/anomaly-category :anomalies/fault)))
    (is (= :phase (r/anomaly-category :anomalies.phase/enter-failed)))
    (is (= :gate (r/anomaly-category :anomalies.gate/validation-failed)))
    (is (= :agent (r/anomaly-category :anomalies.agent/llm-error)))
    (is (= :workflow (r/anomaly-category :anomalies.workflow/max-phases))))

  (testing "returns nil for unknown"
    (is (nil? (r/anomaly-category :not-an-anomaly)))))

;; ============================================================================
;; Errors extraction tests
;; ============================================================================

(deftest errors-test
  (testing "extracts errors in flat format"
    (let [chain (-> (r/create :test)
                    (r/add-success :plan {})
                    (r/add-failure :verify :anomalies.gate/validation-failed
                                   {:error "test failed" :errors ["error1" "error2"]})
                    (r/add-failure :impl :anomalies.phase/agent-failed
                                   {:message "timeout occurred"}))
          errors (r/errors chain)]
      (is (= 2 (count errors)))
      ;; First error
      (is (= :error-verify (:type (first errors))))
      (is (= :verify (:operation (first errors))))
      (is (= :anomalies.gate/validation-failed (:anomaly (first errors))))
      (is (= "test failed" (:message (first errors))))
      (is (= {:errors ["error1" "error2"]} (:data (first errors))))
      ;; Second error
      (is (= :error-impl (:type (second errors))))
      (is (= "timeout occurred" (:message (second errors))))))

  (testing "returns empty vector when no failures"
    (let [chain (-> (r/create :test)
                    (r/add-success :step-1 {}))]
      (is (= [] (r/errors chain))))))
