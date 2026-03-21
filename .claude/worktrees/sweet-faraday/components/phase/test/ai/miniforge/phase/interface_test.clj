(ns ai.miniforge.phase.interface-test
  "Tests for the phase component."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.interface :as phase]
   ;; Require implementations to register methods
   [ai.miniforge.phase.plan :as plan]
   [ai.miniforge.phase.implement :as impl]
   [ai.miniforge.phase.verify :as verify]
   [ai.miniforge.phase.review :as review]
   [ai.miniforge.phase.release :as release]))

;; ============================================================================
;; Registry tests
;; ============================================================================

(deftest get-phase-interceptor-plan-test
  (testing "get-phase-interceptor returns interceptor for :plan"
    (let [interceptor (phase/get-phase-interceptor {:phase :plan})]
      (is (map? interceptor))
      (is (= ::plan/plan (:name interceptor)))
      (is (fn? (:enter interceptor)))
      (is (fn? (:leave interceptor)))
      (is (fn? (:error interceptor))))))

(deftest get-phase-interceptor-implement-test
  (testing "get-phase-interceptor returns interceptor for :implement"
    (let [interceptor (phase/get-phase-interceptor {:phase :implement})]
      (is (map? interceptor))
      (is (= ::impl/implement (:name interceptor)))
      (is (fn? (:enter interceptor))))))

(deftest get-phase-interceptor-verify-test
  (testing "get-phase-interceptor returns interceptor for :verify"
    (let [interceptor (phase/get-phase-interceptor {:phase :verify})]
      (is (map? interceptor))
      (is (= ::verify/verify (:name interceptor))))))

(deftest get-phase-interceptor-review-test
  (testing "get-phase-interceptor returns interceptor for :review"
    (let [interceptor (phase/get-phase-interceptor {:phase :review})]
      (is (map? interceptor))
      (is (= ::review/review (:name interceptor))))))

(deftest get-phase-interceptor-release-test
  (testing "get-phase-interceptor returns interceptor for :release"
    (let [interceptor (phase/get-phase-interceptor {:phase :release})]
      (is (map? interceptor))
      (is (= ::release/release (:name interceptor))))))

(deftest get-phase-interceptor-done-test
  (testing "get-phase-interceptor returns interceptor for :done"
    (let [interceptor (phase/get-phase-interceptor {:phase :done})]
      (is (map? interceptor))
      (is (= ::release/done (:name interceptor))))))

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
                       {:phase :implement
                        :budget {:tokens 50000}})]
      ;; Should have merged config
      (is (= 50000 (get-in interceptor [:config :budget :tokens])))
      ;; Should have default gates
      (is (= [:syntax :lint] (get-in interceptor [:config :gates]))))))

(deftest custom-gates-test
  (testing "custom gates override defaults"
    (let [interceptor (phase/get-phase-interceptor
                       {:phase :implement
                        :gates [:syntax :lint :no-secrets]})]
      (is (= [:syntax :lint :no-secrets] (get-in interceptor [:config :gates]))))))

;; ============================================================================
;; Pipeline building tests
;; ============================================================================

(deftest build-pipeline-test
  (testing "build-pipeline creates interceptor vector"
    (let [workflow {:workflow/pipeline
                    [{:phase :plan}
                     {:phase :implement}
                     {:phase :done}]}
          pipeline (phase/build-pipeline workflow)]
      (is (vector? pipeline))
      (is (= 3 (count pipeline)))
      (is (= ::plan/plan (:name (first pipeline))))
      (is (= ::impl/implement (:name (second pipeline))))
      (is (= ::release/done (:name (last pipeline)))))))

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
                   [{:phase :plan}
                    {:phase :implement}
                    {:phase :done}]})]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest validate-pipeline-missing-phase-test
  (testing "validate-pipeline rejects config without :phase"
    (let [result (phase/validate-pipeline
                  {:workflow/pipeline [{:not-phase :plan}]})]
      (is (not (:valid? result))))))
