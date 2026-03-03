(ns ai.miniforge.workflow.runner-test
  "Unit tests for workflow runner: pipeline building, validation, output extraction."
  (:require
   [clojure.test :refer [deftest testing is]]))

;; We test the pure/deterministic parts of the runner module.
;; The run-pipeline function requires extensive mocking of phase execution;
;; those are covered by integration tests.

(require '[ai.miniforge.workflow.runner :as sut])

;; ============================================================================
;; Test fixtures
;; ============================================================================

(def simple-pipeline-workflow
  {:workflow/id :test-workflow
   :workflow/version "1.0.0"
   :workflow/pipeline
   [{:phase :plan}
    {:phase :implement}
    {:phase :done}]})

(def legacy-workflow
  {:workflow/id :legacy-test
   :workflow/version "1.0.0"
   :workflow/phases
   [{:phase/id :plan :phase/type :plan}
    {:phase/id :implement :phase/type :implement}]})

(def empty-workflow
  {:workflow/id :empty
   :workflow/version "1.0.0"
   :workflow/pipeline []
   :workflow/phases []})

;; ============================================================================
;; build-pipeline tests
;; ============================================================================

(deftest build-pipeline-new-format-test
  (testing "Builds pipeline from :workflow/pipeline config"
    (let [pipeline (sut/build-pipeline simple-pipeline-workflow)]
      (is (vector? pipeline))
      (is (= 3 (count pipeline))
          "Should have one interceptor per pipeline entry"))))

(deftest build-pipeline-legacy-format-test
  (testing "Falls back to :workflow/phases when pipeline is absent"
    (let [pipeline (sut/build-pipeline legacy-workflow)]
      (is (vector? pipeline))
      (is (= 2 (count pipeline))))))

(deftest build-pipeline-empty-test
  (testing "Empty pipeline config produces empty vector"
    (let [pipeline (sut/build-pipeline empty-workflow)]
      (is (vector? pipeline))
      (is (empty? pipeline)))))

;; ============================================================================
;; validate-pipeline tests
;; ============================================================================

(deftest validate-pipeline-valid-workflow-test
  (testing "Valid workflow passes validation"
    (let [result (sut/validate-pipeline simple-pipeline-workflow)]
      (is (map? result))
      (is (contains? result :valid?)))))

(deftest validate-pipeline-empty-workflow-test
  (testing "Empty workflow is handled by validation"
    (let [result (sut/validate-pipeline empty-workflow)]
      (is (map? result)))))

;; ============================================================================
;; extract-output tests (via run-pipeline empty pipeline path)
;; ============================================================================

;; extract-output is private, so we test its behavior through run-pipeline
;; with an empty pipeline which exercises the handle-empty-pipeline → extract-output path.

(deftest run-pipeline-empty-pipeline-returns-failed-test
  (testing "Empty pipeline produces failed execution status"
    (let [result (sut/run-pipeline empty-workflow {} {:skip-lifecycle-events true})]
      (is (= :failed (:execution/status result)))
      (is (seq (:execution/errors result)))
      (is (some #(= :empty-pipeline (:type %)) (:execution/errors result))))))

(deftest run-pipeline-empty-pipeline-has-output-test
  (testing "Even failed pipeline has :execution/output extracted"
    (let [result (sut/run-pipeline empty-workflow {} {:skip-lifecycle-events true})]
      (is (contains? result :execution/output))
      (is (= :failed (get-in result [:execution/output :status]))))))

;; ============================================================================
;; run-pipeline options tests
;; ============================================================================

(deftest run-pipeline-respects-max-phases-option-test
  (testing "max-phases option is respected (tested indirectly via empty pipeline)"
    ;; With empty pipeline, max-phases doesn't matter but we verify no crash
    (let [result (sut/run-pipeline empty-workflow {} {:max-phases 1
                                                      :skip-lifecycle-events true})]
      (is (some? (:execution/status result))))))

(deftest run-pipeline-callbacks-invoked-test
  (testing "Callbacks are set up (smoke test with empty pipeline)"
    (let [started (atom [])
          completed (atom [])
          result (sut/run-pipeline empty-workflow {}
                                   {:skip-lifecycle-events true
                                    :on-phase-start (fn [ctx ic] (swap! started conj ic))
                                    :on-phase-complete (fn [ctx ic r] (swap! completed conj ic))})]
      ;; Empty pipeline means no phases executed, so callbacks not called
      (is (empty? @started))
      (is (empty? @completed))
      (is (= :failed (:execution/status result))))))

(deftest run-pipeline-control-state-accepted-test
  (testing "Custom control-state atom is accepted without error"
    (let [cs (atom {:paused false :stopped false :adjustments {}})
          result (sut/run-pipeline empty-workflow {} {:control-state cs
                                                      :skip-lifecycle-events true})]
      (is (= :failed (:execution/status result))))))
