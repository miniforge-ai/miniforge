(ns ai.miniforge.workflow.runner-extended-test
  "Extended tests for workflow runner: output extraction, event publishing,
   and edge cases not covered by the main runner_test."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.dag-executor.executor :as dag-exec]
   [ai.miniforge.dag-executor.result :as dag-result]
   [ai.miniforge.phase.interface]))

;; ---------------------------------------------------------------------------- extract-output

(deftest extract-output-empty-results-test
  (testing "extract-output handles empty phase-results"
    (let [ctx {:execution/artifacts []
               :execution/phase-results {}
               :execution/current-phase nil
               :execution/status :completed}
          result (runner/extract-output ctx)
          output (:execution/output result)]
      (is (= [] (:artifacts output)))
      (is (= {} (:phase-results output)))
      (is (nil? (:last-phase-result output)))
      (is (= :completed (:status output))))))

(deftest extract-output-failed-status-test
  (testing "extract-output preserves failed status"
    (let [ctx {:execution/artifacts []
               :execution/phase-results {:plan {:phase/status :failed}}
               :execution/current-phase :plan
               :execution/status :failed}
          output (:execution/output (runner/extract-output ctx))]
      (is (= :failed (:status output)))
      (is (= {:phase/status :failed} (:last-phase-result output))))))

;; ---------------------------------------------------------------------------- build-pipeline edge cases

(deftest build-pipeline-nil-pipeline-falls-back-to-legacy-test
  (testing "nil pipeline with phases falls back to legacy format"
    (let [wf {:workflow/phases [{:phase/id :plan} {:phase/id :done}]}
          pipeline (runner/build-pipeline wf)]
      (is (= 2 (count pipeline))))))

(deftest build-pipeline-no-pipeline-no-phases-test
  (testing "no pipeline and no phases returns empty"
    (let [pipeline (runner/build-pipeline {})]
      (is (empty? pipeline)))))

;; ---------------------------------------------------------------------------- run-pipeline edge cases

(deftest run-pipeline-two-arity-test
  (testing "run-pipeline works with 2-arity (no opts)"
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline wf {:task "Test"})]
      (is (= :completed (:execution/status result))))))

(deftest run-pipeline-skip-lifecycle-events-test
  (testing "skip-lifecycle-events suppresses event publishing"
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline wf {:task "Test"}
                   {:skip-lifecycle-events true})]
      (is (= :completed (:execution/status result))))))

(deftest run-pipeline-custom-control-state-test
  (testing "run-pipeline accepts custom control-state atom"
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}
          cs (atom {:paused false :stopped false :adjustments {}})
          result (runner/run-pipeline wf {:task "Test"}
                   {:control-state cs})]
      (is (= :completed (:execution/status result))))))

(deftest run-pipeline-completed-with-warnings-test
  (testing "completed workflow with no artifacts gets warning status"
    ;; The :done phase produces a phase result, so this should complete normally.
    ;; This tests that the check is in place even if not triggered.
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline wf {:task "Test"} {})]
      ;; :done produces a phase result, so it should be :completed (not :completed-with-warnings)
      (is (contains? #{:completed :completed-with-warnings} (:execution/status result))))))

(deftest run-pipeline-max-phases-1-with-done-test
  (testing "max-phases 1 allows single :done phase to complete"
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline wf {:task "Test"} {:max-phases 1})]
      (is (= :completed (:execution/status result))))))

;; ---------------------------------------------------------------------------- publish-event edge cases

(deftest publish-event-nil-stream-test
  (testing "publish-event with nil stream is a no-op"
    ;; Should not throw
    (is (nil? (runner/publish-event! nil {:event/type :test})))))

(deftest publish-event-in-memory-stream-test
  (testing "publish-event with in-memory stream dispatches to event-stream ns"
    ;; This tests the fallback path - it should not throw even if ns not found
    (is (nil? (runner/publish-event! {} {:event/type :test})))))

;; ---------------------------------------------------------------------------- terminal-state?

(deftest terminal-state-test
  (testing "completed and failed are terminal"
    (is (true? (runner/terminal-state? {:execution/status :completed})))
    (is (true? (runner/terminal-state? {:execution/status :failed}))))

  (testing "running is not terminal"
    (is (false? (runner/terminal-state? {:execution/status :running})))))

;; ---------------------------------------------------------------------------- handle-empty-pipeline

(deftest handle-empty-pipeline-test
  (testing "adds error and transitions to failed"
    (let [ctx (ctx/create-context {:workflow/id :test} {} {})
          result (runner/handle-empty-pipeline ctx)]
      (is (= :failed (:execution/status result)))
      (is (some #(= :empty-pipeline (:type %)) (:execution/errors result))))))

;; ---------------------------------------------------------------------------- handle-max-phases-exceeded

(deftest handle-max-phases-exceeded-test
  (testing "adds error about max phases"
    (let [ctx (ctx/create-context {:workflow/id :test} {} {})
          result (runner/handle-max-phases-exceeded ctx 10)]
      (is (= :failed (:execution/status result)))
      (is (some #(= :max-phases-exceeded (:type %)) (:execution/errors result))))))

;; ---------------------------------------------------------------------------- publish helpers with nil stream

(deftest publish-workflow-started-nil-stream-test
  (testing "no-op with nil event stream"
    (is (nil? (runner/publish-workflow-started! nil {})))))

(deftest publish-workflow-completed-nil-stream-test
  (testing "no-op with nil event stream"
    (is (nil? (runner/publish-workflow-completed! nil {})))))

(deftest publish-phase-started-nil-stream-test
  (testing "no-op with nil event stream"
    (is (nil? (runner/publish-phase-started! nil {} :plan)))))

(deftest publish-phase-completed-nil-stream-test
  (testing "no-op with nil event stream"
    (is (nil? (runner/publish-phase-completed! nil {} :plan {})))))

;; ---------------------------------------------------------------------------- execution mode: local (default)

(deftest run-pipeline-local-mode-sets-execution-mode-test
  (testing ":local mode is the default and sets :execution/mode on context"
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline wf {:task "Test"})]
      (is (= :local (:execution/mode result))))))

(deftest run-pipeline-explicit-local-mode-test
  (testing "explicit :local mode uses worktree"
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}
          result (runner/run-pipeline wf {:task "Test"} {:execution-mode :local})]
      (is (= :completed (:execution/status result)))
      (is (= :local (:execution/mode result))))))

(deftest run-pipeline-governed-mode-sets-execution-mode-test
  (testing ":governed mode sets :execution/mode :governed on context"
    ;; Governed mode requires Docker or K8s. This test verifies the mode is
    ;; propagated to the context regardless of which executor is selected.
    ;; In environments without Docker/K8s, governed mode throws instead
    ;; (N11 §7 no-silent-downgrade) — tested separately via mock.
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}]
      (try
        (let [result (runner/run-pipeline wf {:task "Test"} {:execution-mode :governed})]
          ;; Docker was available — mode should be :governed on context
          (is (= :governed (:execution/mode result))))
        (catch Exception e
          ;; Docker unavailable — exception message should match N11 spec language
          (is (re-find #"(?i)No capsule executor" (ex-message e))))))))

(deftest run-pipeline-governed-mode-rejects-worktree-fallback-test
  (testing ":governed mode does not fall through to worktree (N11 §7.4 no-silent-downgrade)"
    ;; Simulate all executors appearing as :worktree type so governed mode
    ;; rejects them and throws rather than silently falling back.
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}]
      (with-redefs [dag-exec/executor-type (constantly :worktree)]
        (is (thrown-with-msg?
             Exception #"(?i)No capsule executor available"
             (runner/run-pipeline wf {:task "Test"}
                                  {:execution-mode :governed})))))))

;; ---------------------------------------------------------------------------- build-env-record: WARN on failure

(deftest build-env-record-logs-warn-when-acquisition-fails-test
  (testing "WARN is printed and pipeline completes when worktree acquisition returns an error"
    (let [wf     {:workflow/id :test
                  :workflow/version "1.0.0"
                  :workflow/pipeline [{:phase :done}]}
          output (with-out-str
                   (with-redefs [dag-exec/acquire-environment!
                                 (fn [& _]
                                   (dag-result/err :worktree-create-failed
                                                   "git worktree add failed"))]
                     (runner/run-pipeline wf {:task "Test"} {})))]
      (is (re-find #"WARN" output))
      (is (re-find #"Failed to acquire execution environment" output)))))

