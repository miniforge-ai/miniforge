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

(ns ai.miniforge.phase.interface-test
  "Tests for the phase component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase.registry :as registry]))

(def phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(clojure.test/use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (binding [loader/phase-loader-config-resource phase-test-config-resource]
      (f))
    (phase/reset-phase-loader!)))

;------------------------------------------------------------------------------ Layer 0
;; Test phase registrations

(defmethod registry/get-phase-interceptor :test-phase
  [config]
  {:name ::test-phase
   :config (registry/merge-with-defaults config)
   :enter identity
   :leave identity
   :error (fn [ctx _ex] ctx)})

(registry/register-phase-defaults!
 :test-phase
 {:phase :test-phase
  :agent :tester
  :gates [:synthetic-gate]
  :budget {:tokens 10
           :iterations 1
           :time-seconds 1}})

(defmethod registry/get-phase-interceptor :test-phase-b
  [config]
  {:name ::test-phase-b
   :config (registry/merge-with-defaults config)
   :enter identity
   :leave identity
   :error (fn [ctx _ex] ctx)})

(registry/register-phase-defaults!
 :test-phase-b
 {:phase :test-phase-b
  :agent :tester
  :gates []
  :budget {:tokens 10
           :iterations 1
           :time-seconds 1}})

;; ============================================================================
;; Registry tests
;; ============================================================================

(deftest get-phase-interceptor-test
  (testing "get-phase-interceptor returns interceptor for a registered phase"
    (let [interceptor (phase/get-phase-interceptor {:phase :test-phase})]
      (is (map? interceptor))
      (is (= ::test-phase (:name interceptor)))
      (is (fn? (:enter interceptor)))
      (is (fn? (:leave interceptor)))
      (is (fn? (:error interceptor))))))

(deftest get-phase-interceptor-unknown-test
  (testing "get-phase-interceptor throws for unknown phase"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown phase type"
         (phase/get-phase-interceptor {:phase :unknown-phase})))))

;; ============================================================================
;; Config merge tests
;; ============================================================================

(deftest config-merge-test
  (testing "custom config merges with defaults"
    (let [interceptor (phase/get-phase-interceptor
                       {:phase :test-phase
                        :budget {:tokens 50000}})]
      ;; Should have merged config
      (is (= 50000 (get-in interceptor [:config :budget :tokens])))
      ;; Should have default gates
      (is (= [:synthetic-gate] (get-in interceptor [:config :gates]))))))

(deftest custom-gates-test
  (testing "custom gates override defaults"
    (let [interceptor (phase/get-phase-interceptor
                       {:phase :test-phase
                        :gates [:syntax :lint :no-secrets]})]
      (is (= [:syntax :lint :no-secrets] (get-in interceptor [:config :gates]))))))

;; ============================================================================
;; Pipeline building tests
;; ============================================================================

(deftest build-pipeline-test
  (testing "build-pipeline creates interceptor vector from distinct phases"
    (let [workflow {:workflow/pipeline
                    [{:phase :test-phase}
                     {:phase :test-phase-b}]}
          pipeline (phase/build-pipeline workflow)]
      (is (vector? pipeline))
      (is (= 2 (count pipeline)))
      (is (= ::test-phase  (:name (first pipeline))))
      (is (= ::test-phase-b (:name (second pipeline)))))))

(deftest build-pipeline-invalid-graph-returns-anomaly-test
  (testing "build-pipeline returns anomaly when pipeline has unknown phase"
    (let [workflow {:workflow/pipeline [{:phase :unknown-nonexistent-phase}]}
          result   (phase/build-pipeline workflow)]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (some? (get-in result [:anomaly/data :validation])))))

  (testing "build-pipeline returns anomaly for empty pipeline"
    (let [workflow {:workflow/pipeline []}
          result   (phase/build-pipeline workflow)]
      (is (anomaly/anomaly? result)))))

;; ============================================================================
;; Validation tests
;; ============================================================================

(deftest validate-pipeline-empty-test
  (testing "validate-pipeline rejects empty pipeline"
    (let [result (phase/validate-pipeline {:workflow/pipeline []})]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest validate-pipeline-valid-test
  (testing "validate-pipeline accepts valid pipeline with distinct phases"
    (let [result (phase/validate-pipeline
                  {:workflow/pipeline
                   [{:phase :test-phase}
                    {:phase :test-phase-b}]})]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest validate-pipeline-missing-phase-test
  (testing "validate-pipeline rejects config without :phase"
    (let [result (phase/validate-pipeline
                  {:workflow/pipeline [{:not-phase :plan}]})]
      (is (not (:valid? result))))))

(deftest validate-pipeline-unknown-phase-test
  (testing "validate-pipeline rejects unregistered phase keyword"
    (let [result (phase/validate-pipeline
                  {:workflow/pipeline [{:phase :no-such-phase-anywhere}]})]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

;; ============================================================================
;; validate-pipeline-graph tests
;; ============================================================================

(deftest validate-pipeline-graph-valid-test
  (testing "validate-pipeline-graph returns valid for structurally sound pipeline"
    (let [result (phase/validate-pipeline-graph
                  [{:phase :test-phase}
                   {:phase :test-phase-b}])]
      (is (:valid? result))
      (is (empty? (:errors result)))
      (is (vector? (:warnings result))))))

(deftest validate-pipeline-graph-empty-test
  (testing "validate-pipeline-graph rejects empty pipeline"
    (let [result (phase/validate-pipeline-graph [])]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest validate-pipeline-graph-duplicate-phases-test
  (testing "validate-pipeline-graph rejects duplicate :phase keywords"
    (let [result (phase/validate-pipeline-graph
                  [{:phase :test-phase}
                   {:phase :test-phase}])]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest validate-pipeline-graph-malformed-entry-test
  (testing "validate-pipeline-graph rejects malformed entries (no :phase key)"
    (let [result (phase/validate-pipeline-graph [{:not-phase :foo}])]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest validate-pipeline-graph-with-interceptor-check-test
  (testing "validate-pipeline-graph with interceptor check flags unregistered phase"
    (let [result (phase/validate-pipeline-graph
                  [{:phase :completely-unknown-phase}]
                  {:check-interceptors?       true
                   :interceptor-registered-fn #(contains? (phase/list-phases) %)})]
      (is (not (:valid? result)))
      (is (seq (:errors result)))))

  (testing "validate-pipeline-graph with interceptor check passes for registered phases"
    (let [result (phase/validate-pipeline-graph
                  [{:phase :test-phase}
                   {:phase :test-phase-b}]
                  {:check-interceptors?       true
                   :interceptor-registered-fn #(contains? (phase/list-phases) %)})]
      (is (:valid? result))
      (is (empty? (:errors result))))))

;; ============================================================================
;; validate-graph pass-through tests
;; ============================================================================

(deftest validate-graph-pass-through-test
  (testing "validate-graph returns valid result for a correct graph"
    (let [graph {:nodes          #{:a :b :failed :completed}
                 :edges          [{:from :a :to :b      :label :next       :meta {}}
                                  {:from :a :to :failed :label :on-failure :meta {}}
                                  {:from :b :to :failed :label :on-failure :meta {}}
                                  {:from :a :to :completed :label :already-done :meta {}}
                                  {:from :b :to :completed :label :already-done :meta {}}]
                 :terminal-nodes #{:failed :completed}
                 :phase-nodes    #{:a :b}}
          result (phase/validate-graph graph)]
      (is (map? result))
      (is (contains? result :valid?))
      (is (contains? result :errors))
      (is (contains? result :warnings)))))

(deftest validate-graph-with-opts-pass-through-test
  (testing "validate-graph with opts is forwarded correctly"
    (let [graph  {:nodes          #{:a :failed :completed}
                  :edges          [{:from :a :to :failed    :label :on-failure  :meta {}}
                                   {:from :a :to :completed :label :already-done :meta {}}]
                  :terminal-nodes #{:failed :completed}
                  :phase-nodes    #{:a}}
          result (phase/validate-graph graph
                                       {:check-interceptors?       true
                                        :interceptor-registered-fn (constantly true)})]
      (is (map? result))
      (is (contains? result :valid?)))))

;; ============================================================================
;; graph-valid? pass-through tests
;; ============================================================================

(deftest graph-valid-pass-through-test
  (testing "graph-valid? returns true for a structurally sound graph"
    (let [graph {:nodes          #{:a :failed :completed}
                 :edges          [{:from :a :to :failed    :label :on-failure   :meta {}}
                                  {:from :a :to :completed :label :already-done :meta {}}]
                 :terminal-nodes #{:failed :completed}
                 :phase-nodes    #{:a}}]
      (is (true? (phase/graph-valid? graph)))))

  (testing "graph-valid? returns false for an invalid graph"
    (let [graph {:nodes          #{:a}
                 :edges          []
                 :terminal-nodes #{}
                 :phase-nodes    #{:a}}]
      (is (false? (phase/graph-valid? graph))))))

;; ============================================================================
;; Status predicate tests
;; ============================================================================

(deftest inner-agent-failure-overrides-outer-completed-status-test
  (testing "outer interceptor completion does not hide an inner agent failure"
    (let [phase-result {:status :completed
                        :result {:status :error
                                 :error {:message "planner exploded"}}}]
      (is (phase/failed? phase-result))
      (is (not (phase/succeeded? phase-result))))))

(deftest completed-phase-still-succeeds-when-inner-result-succeeds-test
  (testing "healthy inner results preserve successful outer completion"
    (let [phase-result {:status :completed
                        :result {:status :success}}]
      (is (phase/succeeded? phase-result))
      (is (not (phase/failed? phase-result))))))

(deftest retrying-phase-preserves-retrying-outer-status-test
  (testing "inner agent failures do not overwrite an explicit retrying status"
    (let [phase-result {:status :retrying
                        :result {:status :error
                                 :error {:message "planner exploded"}}}]
      (is (phase/retrying? phase-result))
      (is (not (phase/failed? phase-result))))))
