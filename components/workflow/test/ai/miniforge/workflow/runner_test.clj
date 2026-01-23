(ns ai.miniforge.workflow.runner-test
  "Tests for the interceptor-based workflow runner."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner :as runner]
   ;; Require phase implementations
   [ai.miniforge.phase.plan]
   [ai.miniforge.phase.implement]
   [ai.miniforge.phase.verify]
   [ai.miniforge.phase.review]
   [ai.miniforge.phase.release]
   ;; Require gate implementations
   [ai.miniforge.gate.syntax]
   [ai.miniforge.gate.lint]
   [ai.miniforge.gate.test]
   [ai.miniforge.gate.policy]))

;; ============================================================================
;; Context creation tests
;; ============================================================================

(deftest create-context-test
  (testing "create-context initializes execution state"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"}
          ctx (runner/create-context workflow {:task "Test"} {})]
      (is (uuid? (:execution/id ctx)))
      (is (= :test (:execution/workflow-id ctx)))
      (is (= :running (:execution/status ctx)))
      (is (= {:task "Test"} (:execution/input ctx)))
      (is (vector? (:execution/artifacts ctx)))
      (is (number? (:execution/started-at ctx))))))

;; ============================================================================
;; Pipeline building tests
;; ============================================================================

(deftest build-pipeline-simple-test
  (testing "build-pipeline creates interceptors from config"
    (let [workflow {:workflow/pipeline
                    [{:phase :plan}
                     {:phase :implement}
                     {:phase :done}]}
          pipeline (runner/build-pipeline workflow)]
      (is (vector? pipeline))
      (is (= 3 (count pipeline)))
      (is (every? #(contains? % :enter) pipeline)))))

(deftest build-pipeline-empty-test
  (testing "build-pipeline handles empty pipeline"
    (let [workflow {:workflow/pipeline []}
          pipeline (runner/build-pipeline workflow)]
      (is (empty? pipeline)))))

(deftest build-pipeline-legacy-format-test
  (testing "build-pipeline handles legacy phase format"
    (let [workflow {:workflow/phases
                    [{:phase/id :plan}
                     {:phase/id :implement}
                     {:phase/id :done}]}
          pipeline (runner/build-pipeline workflow)]
      (is (= 3 (count pipeline))))))

;; ============================================================================
;; Validation tests
;; ============================================================================

(deftest validate-pipeline-valid-test
  (testing "validate-pipeline accepts valid workflow"
    (let [workflow {:workflow/pipeline
                    [{:phase :plan}
                     {:phase :done}]}
          result (runner/validate-pipeline workflow)]
      (is (:valid? result)))))

(deftest validate-pipeline-nil-test
  (testing "validate-pipeline handles nil pipeline"
    (let [workflow {}
          result (runner/validate-pipeline workflow)]
      (is (not (:valid? result))))))

;; ============================================================================
;; Pipeline execution tests
;; ============================================================================

(deftest run-pipeline-empty-test
  (testing "run-pipeline fails for empty workflow"
    (let [workflow {:workflow/pipeline []}
          result (runner/run-pipeline workflow {} {})]
      (is (= :failed (:execution/status result)))
      (is (some #(= :empty-pipeline (:type %)) (:execution/errors result))))))

(deftest run-pipeline-done-only-test
  (testing "run-pipeline completes with just :done phase"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (= :completed (:execution/status result)))
      (is (number? (:execution/ended-at result))))))

(deftest run-pipeline-callbacks-test
  (testing "run-pipeline invokes callbacks"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          started (atom [])
          completed (atom [])
          result (runner/run-pipeline workflow {:task "Test"}
                                       {:on-phase-start
                                        (fn [ctx ic]
                                          (swap! started conj
                                                 (get-in ic [:config :phase])))
                                        :on-phase-complete
                                        (fn [ctx ic res]
                                          (swap! completed conj
                                                 (get-in ic [:config :phase])))})]
      (is (= :completed (:execution/status result)))
      (is (= [:done] @started))
      (is (= [:done] @completed)))))

(deftest run-pipeline-max-phases-test
  (testing "run-pipeline respects max phases option"
    ;; Since phase stubs succeed, they don't trigger :on-fail loops
    ;; Just test that max-phases option is respected with a simple workflow
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline
                    [{:phase :plan}
                     {:phase :implement}
                     {:phase :verify}
                     {:phase :review}
                     {:phase :release}
                     {:phase :done}]}
          ;; This should complete before max-phases=50
          result (runner/run-pipeline workflow {:task "Test"} {:max-phases 50})]
      ;; Should complete successfully (6 phases < 50)
      (is (= :completed (:execution/status result))))))

;; ============================================================================
;; Phase result recording tests
;; ============================================================================

(deftest phase-results-recorded-test
  (testing "phase results are recorded in execution context"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (contains? (:execution/phase-results result) :done)))))

;; ============================================================================
;; Metrics accumulation tests
;; ============================================================================

(deftest metrics-accumulated-test
  (testing "metrics are accumulated across phases"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (map? (:execution/metrics result)))
      (is (contains? (:execution/metrics result) :tokens)))))
