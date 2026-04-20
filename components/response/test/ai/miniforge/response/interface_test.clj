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

(ns ai.miniforge.response.interface-test
  "Tests for the response component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
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
  (testing "add-failure adds entry with anomaly keyword"
    (let [chain (-> (r/create :test)
                    (r/add-failure :step-1 :anomalies/fault {:error "boom"}))]
      (is (= 1 (r/entry-count chain)))
      (is (r/failed? chain))
      (is (= :anomalies/fault (r/last-anomaly chain)))
      (is (= {:error "boom"} (r/last-response chain)))))

  (testing "add-failure accepts anomaly map"
    (let [anom (r/make-anomaly :anomalies.gate/validation-failed "Test failed"
                               {:anomaly/phase :verify})
          chain (-> (r/create :test)
                    (r/add-failure :step-1 anom {:errors ["e1"]}))]
      (is (r/failed? chain))
      ;; :anomaly key still holds the keyword for backward compat
      (is (= :anomalies.gate/validation-failed (r/last-anomaly chain)))
      ;; :anomaly-map holds the full map
      (let [entry (r/last-entry chain)]
        (is (r/anomaly-map? (:anomaly-map entry)))
        (is (= :verify (get-in entry [:anomaly-map :anomaly/phase])))))))

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
  (testing "exception adds failure entry with anomaly map"
    (let [chain (-> (r/create :test)
                    (r/execute-with-handling :step-1 r/default-anomaly-classifier
                                             #(throw (ex-info "boom" {:code 123}))))]
      (is (r/failed? chain))
      (is (= :anomalies/fault (r/last-anomaly chain)))
      (is (= "boom" (:error (r/last-response chain))))
      (is (= {:code 123} (:data (r/last-response chain))))
      ;; New: anomaly-map is present
      (let [entry (r/last-entry chain)]
        (is (r/anomaly-map? (:anomaly-map entry)))
        (is (= "boom" (:anomaly/message (:anomaly-map entry))))))))

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

;; ============================================================================
;; Anomaly map constructor tests
;; ============================================================================

(deftest make-anomaly-test
  (testing "creates anomaly map with required keys"
    (let [anom (r/make-anomaly :anomalies/fault "Something broke")]
      (is (= :anomalies/fault (:anomaly/category anom)))
      (is (= "Something broke" (:anomaly/message anom)))
      (is (uuid? (:anomaly/id anom)))
      (is (inst? (:anomaly/timestamp anom)))))

  (testing "merges domain context"
    (let [anom (r/make-anomaly :anomalies.gate/validation-failed "Test failed"
                               {:anomaly/phase :verify
                                :anomaly/operation :validate})]
      (is (= :anomalies.gate/validation-failed (:anomaly/category anom)))
      (is (= :verify (:anomaly/phase anom)))
      (is (= :validate (:anomaly/operation anom)))))

  (testing "context does not overwrite required keys"
    (let [anom (r/make-anomaly :anomalies/fault "original"
                               {:anomaly/category :anomalies/timeout
                                :anomaly/message "overwritten"})]
      ;; merge puts context first, then required keys, so required wins
      (is (= :anomalies/fault (:anomaly/category anom)))
      (is (= "original" (:anomaly/message anom))))))

(deftest anomaly-map?-test
  (testing "recognizes anomaly maps"
    (is (r/anomaly-map? (r/make-anomaly :anomalies/fault "broken")))
    (is (r/anomaly-map? {:anomaly/category :anomalies/fault})))

  (testing "rejects non-anomaly values"
    (is (not (r/anomaly-map? nil)))
    (is (not (r/anomaly-map? :anomalies/fault)))
    (is (not (r/anomaly-map? "error")))
    (is (not (r/anomaly-map? {:error "not an anomaly"})))
    (is (not (r/anomaly-map? 42)))))

(deftest from-exception-test
  (testing "converts ex-info to anomaly map"
    (let [ex (ex-info "Timeout" {:phase :verify})
          anom (r/from-exception ex)]
      (is (r/anomaly-map? anom))
      (is (= :anomalies/fault (:anomaly/category anom)))
      (is (= "Timeout" (:anomaly/message anom)))
      (is (= "Timeout" (:anomaly/ex-message anom)))
      (is (= {:phase :verify} (:anomaly/ex-data anom)))))

  (testing "uses :anomaly key from ex-data when present"
    (let [ex (ex-info "oops" {:anomaly :anomalies/timeout})
          anom (r/from-exception ex)]
      (is (= :anomalies/timeout (:anomaly/category anom)))))

  (testing "uses classifier-fn when provided"
    (let [ex (ex-info "boom" {})
          classifier (fn [_] :anomalies/busy)
          anom (r/from-exception ex classifier)]
      (is (= :anomalies/busy (:anomaly/category anom)))))

  (testing "classifier-fn takes precedence over ex-data :anomaly"
    (let [ex (ex-info "boom" {:anomaly :anomalies/timeout})
          classifier (fn [_] :anomalies/busy)
          anom (r/from-exception ex classifier)]
      (is (= :anomalies/busy (:anomaly/category anom)))))

  (testing "captures exception class"
    (let [ex (java.util.concurrent.TimeoutException. "timed out")
          anom (r/from-exception ex)]
      (is (string? (:anomaly/ex-class anom))))))

(deftest gate-anomaly-test
  (testing "creates anomaly with gate errors"
    (let [gate-errors [{:code :syntax-error
                        :message "Parse error"
                        :location {:file "foo.clj" :line 10 :column 5}}]
          anom (r/gate-anomaly :anomalies.gate/validation-failed
                               "Syntax gate failed"
                               gate-errors)]
      (is (r/anomaly-map? anom))
      (is (= :anomalies.gate/validation-failed (:anomaly/category anom)))
      (is (= gate-errors (:anomaly.gate/errors anom)))))

  (testing "merges additional context"
    (let [anom (r/gate-anomaly :anomalies.gate/check-failed
                               "Gate threw"
                               [{:code :test-failed :message "assertion"}]
                               {:anomaly/phase :verify})]
      (is (= :verify (:anomaly/phase anom))))))

(deftest agent-anomaly-test
  (testing "creates anomaly with agent role"
    (let [anom (r/agent-anomaly :anomalies.agent/invoke-failed
                                "Agent timed out"
                                :implementer)]
      (is (r/anomaly-map? anom))
      (is (= :anomalies.agent/invoke-failed (:anomaly/category anom)))
      (is (= :implementer (:anomaly.agent/role anom)))))

  (testing "merges additional context"
    (let [anom (r/agent-anomaly :anomalies.agent/llm-error
                                "Model error"
                                :planner
                                {:anomaly.llm/model "claude-sonnet-4"})]
      (is (= "claude-sonnet-4" (:anomaly.llm/model anom))))))

;; ============================================================================
;; Builder error-details tests (empty message fix)
;; ============================================================================

(deftest error-details-never-empty-test
  (testing "exception with nil message produces non-empty error message"
    (let [ex (NullPointerException.)
          resp (r/error ex)]
      (is (some? (get-in resp [:error :message])))
      (is (pos? (count (get-in resp [:error :message]))))))

  (testing "exception with empty message produces non-empty error message"
    (let [ex (Exception. "")
          resp (r/error ex)]
      (is (pos? (count (get-in resp [:error :message]))))))

  (testing "nil message argument produces non-empty error message"
    (let [resp (r/error nil)]
      (is (pos? (count (get-in resp [:error :message]))))))

  (testing "normal exception message preserved"
    (let [resp (r/error (Exception. "Timeout in verify phase"))]
      (is (= "Timeout in verify phase" (get-in resp [:error :message])))))

  (testing "failure with nil-message exception produces non-empty error"
    (let [resp (r/failure (NullPointerException.))]
      (is (some? (get-in resp [:error :message])))
      (is (pos? (count (get-in resp [:error :message])))))))

;; ============================================================================
;; Result map predicates (success?/error?)
;; ============================================================================

(deftest success?-test
  (testing "true for builder success outputs"
    (is (true? (r/success? (r/success {:foo 1}))))
    (is (true? (r/success? (r/success {} {:tokens 10})))))

  (testing "false for error/failure builder outputs"
    (is (false? (r/success? (r/error "boom"))))
    (is (false? (r/success? (r/failure "boom")))))

  (testing "false for nil and arbitrary maps"
    (is (false? (r/success? nil)))
    (is (false? (r/success? {})))
    (is (false? (r/success? {:status :pending})))))

(deftest error?-test
  (testing "true for error builder output (:status :error)"
    (is (true? (r/error? (r/error "boom")))))

  (testing "true for failure builder output (:success false)"
    (is (true? (r/error? (r/failure "boom")))))

  (testing "true for legacy :status :failed shape"
    (is (true? (r/error? {:status :failed :error {:message "x"}}))))

  (testing "false for success builder output"
    (is (false? (r/error? (r/success {:foo 1})))))

  (testing "false for nil and arbitrary maps"
    (is (false? (r/error? nil)))
    (is (false? (r/error? {})))
    (is (false? (r/error? {:status :pending})))))

;; ============================================================================
;; Boundary translator tests
;; ============================================================================

(deftest anomaly->http-status-test
  (testing "maps general anomalies to HTTP status codes"
    (is (= 400 (r/anomaly->http-status :anomalies/incorrect)))
    (is (= 403 (r/anomaly->http-status :anomalies/forbidden)))
    (is (= 404 (r/anomaly->http-status :anomalies/not-found)))
    (is (= 409 (r/anomaly->http-status :anomalies/conflict)))
    (is (= 429 (r/anomaly->http-status :anomalies/busy)))
    (is (= 500 (r/anomaly->http-status :anomalies/fault)))
    (is (= 501 (r/anomaly->http-status :anomalies/unsupported)))
    (is (= 503 (r/anomaly->http-status :anomalies/unavailable)))
    (is (= 504 (r/anomaly->http-status :anomalies/timeout))))

  (testing "maps domain anomalies"
    (is (= 422 (r/anomaly->http-status :anomalies.gate/validation-failed)))
    (is (= 502 (r/anomaly->http-status :anomalies.agent/llm-error)))
    (is (= 429 (r/anomaly->http-status :anomalies.phase/budget-exceeded))))

  (testing "defaults to 500 for unknown"
    (is (= 500 (r/anomaly->http-status :unknown/anomaly)))))

(deftest anomaly->http-response-test
  (testing "produces Ring response map"
    (let [anom (r/make-anomaly :anomalies/not-found "User 123 not found")
          resp (r/anomaly->http-response anom)]
      (is (= 404 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"])))
      (is (= "not-found" (get-in resp [:body :error :code])))
      (is (string? (get-in resp [:body :error :message]))))))

(deftest anomaly->user-message-test
  (testing "returns user-friendly message for known categories"
    (let [anom (r/make-anomaly :anomalies/timeout "LLM call timed out after 30s")]
      (is (= "The operation timed out. Please try again."
             (r/anomaly->user-message anom)))))

  (testing "never exposes internal message"
    (let [anom (r/make-anomaly :anomalies/fault "NullPointerException at foo.clj:42")]
      (is (not (str/includes? (r/anomaly->user-message anom) "NullPointer")))))

  (testing "falls back to generic message for unknown categories"
    (let [anom (r/make-anomaly :anomalies.custom/something "internal detail")]
      (is (string? (r/anomaly->user-message anom))))))

(deftest anomaly->log-data-test
  (testing "extracts structured log data"
    (let [anom (r/make-anomaly :anomalies.agent/llm-error "Model returned 500"
                               {:anomaly/phase :implement
                                :anomaly.agent/role :implementer})
          log-data (r/anomaly->log-data anom)]
      (is (= :anomalies.agent/llm-error (:anomaly/category log-data)))
      (is (= "Model returned 500" (:anomaly/message log-data)))
      (is (true? (:anomaly/retryable? log-data)))
      (is (= :implement (:anomaly/phase log-data)))
      (is (= :implementer (:anomaly.agent/role log-data)))))

  (testing "omits nil optional fields"
    (let [anom (r/make-anomaly :anomalies/fault "broke")
          log-data (r/anomaly->log-data anom)]
      (is (not (contains? log-data :anomaly/phase)))
      (is (not (contains? log-data :anomaly.agent/role))))))

(deftest anomaly->event-data-test
  (testing "produces event-compatible map"
    (let [anom (r/make-anomaly :anomalies/timeout "Phase timed out"
                               {:anomaly/phase :verify})
          event-data (r/anomaly->event-data anom)]
      (is (= "Phase timed out" (:message event-data)))
      (is (= :anomalies/timeout (:anomaly-code event-data)))
      (is (true? (:retryable? event-data)))
      (is (= :verify (:phase event-data))))))

(deftest anomaly->outcome-evidence-test
  (testing "produces evidence-bundle outcome fields"
    (let [anom (r/make-anomaly :anomalies.gate/validation-failed "Test failed"
                               {:anomaly/phase :verify
                                :anomaly.gate/errors [{:code :test-failed}]})
          outcome (r/anomaly->outcome-evidence anom)]
      (is (false? (:outcome/success outcome)))
      (is (= :anomalies.gate/validation-failed (:outcome/anomaly-code outcome)))
      (is (= "Test failed" (:outcome/error-message outcome)))
      (is (= :verify (:outcome/error-phase outcome)))
      (is (= [{:code :test-failed}]
             (get-in outcome [:outcome/error-details :anomaly.gate/errors]))))))

(deftest coerce-test
  (testing "passes through existing anomaly maps"
    (let [anom (r/make-anomaly :anomalies/fault "already good")]
      (is (identical? anom (r/coerce anom)))))

  (testing "converts builder error shape"
    (let [anom (r/coerce {:status :error
                                          :error {:message "bad input"}})]
      (is (r/anomaly-map? anom))
      (is (= "bad input" (:anomaly/message anom)))))

  (testing "converts gate result shape"
    (let [anom (r/coerce {:gate/passed? false
                                          :gate/id :syntax
                                          :gate/errors [{:code :syntax-error
                                                         :message "parse error"}]})]
      (is (r/anomaly-map? anom))
      (is (= :anomalies.gate/validation-failed (:anomaly/category anom)))
      (is (= 1 (count (:anomaly.gate/errors anom))))))

  (testing "converts success-false shape"
    (let [anom (r/coerce {:success false :error "timeout"})]
      (is (r/anomaly-map? anom))
      (is (= "timeout" (:anomaly/message anom)))))

  (testing "converts success?-false shape"
    (let [anom (r/coerce {:success? false :error "nope"})]
      (is (r/anomaly-map? anom))
      (is (= "nope" (:anomaly/message anom)))))

  (testing "converts ad-hoc code/message shape"
    (let [anom (r/coerce {:code :generation-error
                                          :message "LLM failed"})]
      (is (r/anomaly-map? anom))
      (is (= "LLM failed" (:anomaly/message anom)))
      (is (= :generation-error (:anomaly/legacy-code anom)))))

  (testing "accepts custom default category"
    (let [anom (r/coerce {:success false :error "oops"}
                                         :anomalies/timeout)]
      (is (= :anomalies/timeout (:anomaly/category anom)))))

  (testing "handles unknown shape as fallback"
    (let [anom (r/coerce "just a string")]
      (is (r/anomaly-map? anom))
      (is (= :anomalies/fault (:anomaly/category anom))))))
