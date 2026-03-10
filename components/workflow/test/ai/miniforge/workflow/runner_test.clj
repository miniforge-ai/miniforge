(ns ai.miniforge.workflow.runner-test
  "Unit tests for the interceptor-based workflow runner.
   Tests that execute real phase pipelines (plan, implement, etc.)
   live in runner-integration-test under project tests."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.phase.interface]))

;; ============================================================================
;; Context creation tests
;; ============================================================================

(deftest create-context-test
  (testing "create-context initializes execution state"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"}
          ctx (ctx/create-context workflow {:task "Test"} {})]
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
                                        (fn [_ctx ic]
                                          (swap! started conj
                                                 (get-in ic [:config :phase])))
                                        :on-phase-complete
                                        (fn [_ctx ic _res]
                                          (swap! completed conj
                                                 (get-in ic [:config :phase])))})]
      (is (= :completed (:execution/status result)))
      (is (= [:done] @started))
      (is (= [:done] @completed)))))

(deftest run-pipeline-max-phases-test
  (testing "run-pipeline completes a simple workflow within max-phases limit"
    ;; Use :done phase only since other phases require LLM infrastructure
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {:max-phases 50})]
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

;; ============================================================================
;; Response chain tests
;; ============================================================================

(deftest response-chain-created-test
  (testing "response chain is initialized in context"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"}
          ctx (ctx/create-context workflow {:task "Test"} {})]
      (is (contains? ctx :execution/response-chain))
      (is (= :test (:operation (:execution/response-chain ctx))))
      (is (true? (:succeeded? (:execution/response-chain ctx)))))))

;; ============================================================================
;; FSM state tracking tests
;; ============================================================================

(deftest fsm-state-tracked-test
  (testing "FSM state is tracked in context"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"}
          ctx (ctx/create-context workflow {:task "Test"} {})]
      (is (contains? ctx :execution/fsm-state))
      (is (map? (:execution/fsm-state ctx)))
      (is (= :running (:_state (:execution/fsm-state ctx))))))

  (testing "FSM state transitions on completion"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (= :completed (:execution/status result)))
      (is (= :completed (:_state (:execution/fsm-state result))))))

  (testing "FSM state transitions on failure"
    (let [workflow {:workflow/pipeline []}
          result (runner/run-pipeline workflow {} {})]
      (is (= :failed (:execution/status result)))
      (is (= :failed (:_state (:execution/fsm-state result)))))))

;; ============================================================================
;; Output extraction tests
;; ============================================================================

(deftest extract-output-shape-test
  (testing "extract-output populates :execution/output with expected shape"
    (let [;; Build a minimal context that looks like a completed pipeline
          fake-ctx {:execution/artifacts [{:type :file :path "out.txt"}]
                    :execution/phase-results {:plan {:phase/status :succeeded}
                                              :done {:phase/status :succeeded}}
                    :execution/current-phase :done
                    :execution/status :completed}
          ;; Call extract-output via its var (private fn)
          result (ai.miniforge.workflow.runner/extract-output fake-ctx)
          output (:execution/output result)]
      (is (some? output) ":execution/output should be present")
      (is (= [{:type :file :path "out.txt"}] (:artifacts output)))
      (is (= {:plan {:phase/status :succeeded}
              :done {:phase/status :succeeded}}
             (:phase-results output)))
      (is (= {:phase/status :succeeded} (:last-phase-result output)))
      (is (= :completed (:status output))))))

(deftest run-pipeline-returns-execution-output-test
  (testing "run-pipeline returns context with :execution/output populated"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (= :completed (:execution/status result)))
      (is (some? (:execution/output result))
          ":execution/output should be present after run-pipeline")
      (let [output (:execution/output result)]
        (is (= :completed (:status output)))
        (is (vector? (:artifacts output)))
        (is (map? (:phase-results output)))
        (is (contains? (:phase-results output) :done))
        (is (some? (:last-phase-result output)))))))

(deftest context-initializes-output-nil-test
  (testing "create-context initializes :execution/output as nil"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"}
          ctx (ctx/create-context workflow {:task "Test"} {})]
      (is (contains? ctx :execution/output))
      (is (nil? (:execution/output ctx))))))
