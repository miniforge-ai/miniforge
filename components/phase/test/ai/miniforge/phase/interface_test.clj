(ns ai.miniforge.phase.interface-test
  "Tests for the phase component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.registry :as registry]))

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
  (testing "build-pipeline creates interceptor vector"
    (let [workflow {:workflow/pipeline
                    [{:phase :test-phase}
                     {:phase :test-phase}
                     {:phase :test-phase}]}
          pipeline (phase/build-pipeline workflow)]
      (is (vector? pipeline))
      (is (= 3 (count pipeline)))
      (is (= ::test-phase (:name (first pipeline))))
      (is (= ::test-phase (:name (second pipeline))))
      (is (= ::test-phase (:name (last pipeline)))))))

;; ============================================================================
;; Validation tests
;; ============================================================================

(deftest validate-pipeline-empty-test
  (testing "validate-pipeline rejects empty pipeline"
    (let [result (phase/validate-pipeline {:workflow/pipeline []})]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest validate-pipeline-valid-test
  (testing "validate-pipeline accepts valid pipeline"
    (let [result (phase/validate-pipeline
                  {:workflow/pipeline
                   [{:phase :test-phase}
                    {:phase :test-phase}
                    {:phase :test-phase}]})]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest validate-pipeline-missing-phase-test
  (testing "validate-pipeline rejects config without :phase"
    (let [result (phase/validate-pipeline
                  {:workflow/pipeline [{:not-phase :plan}]})]
      (is (not (:valid? result))))))
