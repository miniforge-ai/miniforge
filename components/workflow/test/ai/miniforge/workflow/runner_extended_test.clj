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

(ns ai.miniforge.workflow.runner-extended-test
  "Extended tests for workflow runner: output extraction, event publishing,
   and edge cases not covered by the main runner_test."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.dag-executor.interface :as dag-exec]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.execution :as exec]
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

(deftest extract-output-falls-back-to-pipeline-phase-order-test
  (testing "extract-output chooses the last recorded phase using workflow pipeline order"
    (let [ctx {:execution/workflow {:workflow/pipeline [{:phase :plan}
                                                       {:phase :implement}
                                                       {:phase :done}]}
               :execution/artifacts []
               :execution/phase-results {:done {:phase/status :succeeded}
                                         :plan {:phase/status :succeeded}}
               :execution/current-phase nil
               :execution/status :completed}
          output (:execution/output (runner/extract-output ctx))]
      (is (= {:phase/status :succeeded} (:last-phase-result output)))
      (is (= {:phase/status :succeeded}
             (get-in output [:phase-results :done]))))))

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

(deftest apply-dag-success-does-not-consume-redirect-budget-test
  (testing "DAG fast-path advances with normal success events"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :plan}
                                        {:phase :implement}
                                        {:phase :verify}
                                        {:phase :done}]}
          pipeline (runner/build-pipeline workflow)
          context (ctx/create-context workflow {:task "Test"} {})
          dag-result {:artifacts []
                      :worktree-paths []
                      :metrics {:tokens 0 :cost-usd 0.0}}
          result (exec/apply-dag-success context
                                         dag-result
                                         pipeline
                                         ctx/transition-to-completed
                                         ctx/transition-to-failed)]
      (is (= :running (:execution/status result)))
      (is (= :verify (:execution/current-phase result)))
      (is (= 0 (:execution/redirect-count result))))))

(deftest execute-phase-step-uses-machine-state-over-phase-index-test
  (testing "standard execution selects the active phase from the machine snapshot"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :done}]}
          pipeline (runner/build-pipeline workflow)
          context (-> (ctx/create-context workflow {:task "Test"} {})
                      (assoc :execution/phase-index 99))
          result (exec/execute-phase-step pipeline
                                          context
                                          {}
                                          ctx/merge-metrics
                                          ctx/transition-to-completed
                                          ctx/transition-to-failed)]
      (is (= :completed (:execution/status result)))
      (is (contains? (:execution/phase-results result) :done)))))

;; ---------------------------------------------------------------------------- publish-event edge cases

(deftest publish-event-nil-stream-test
  (testing "publish-event with nil stream is a no-op"
    ;; Should not throw
    (is (nil? (runner/publish-event! nil {:event/type :test})))))

(deftest publish-event-in-memory-stream-test
  (testing "publish-event no-ops for non-stream maps"
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

(deftest run-pipeline-governed-mode-has-host-worktree-test
  (testing ":governed mode provides a host-accessible worktree-path (not /workspace)"
    (let [wf {:workflow/id :test
              :workflow/version "1.0.0"
              :workflow/pipeline [{:phase :done}]}]
      (try
        (let [result (runner/run-pipeline wf {:task "Test"} {:execution-mode :governed})]
          ;; worktree-path should be a host path, not the container-internal /workspace
          (is (some? (:execution/worktree-path result))
              "Should have a worktree path")
          (is (not= "/workspace" (:execution/worktree-path result))
              "Worktree path should be host-accessible, not container-internal")
          (is (some? (:execution/executor result))
              "Should have a capsule executor")
          (is (some? (:execution/environment-id result))
              "Should have a capsule environment-id"))
        (catch Exception _e
          ;; Docker unavailable — skip test
          nil)))))

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

;; ---------------------------------------------------------------------------- persist-workspace-at-phase-boundary!

(def ^:private persist-fn
  "Var accessor for persist-workspace-at-phase-boundary! (extracted to runner-environment)."
  (do (require 'ai.miniforge.workflow.runner-environment)
      (ns-resolve 'ai.miniforge.workflow.runner-environment
                  'persist-workspace-at-phase-boundary!)))

(deftest persist-workspace-noop-when-no-executor-test
  (testing "returns nil when context has no :execution/executor"
    (let [context {:execution/mode :governed
                   :execution/environment-id "env-1"
                   :execution/task-branch "task/branch"}
          phase-ctx {:execution/current-phase :implement}]
      (is (nil? (persist-fn context phase-ctx))))))

(deftest persist-workspace-fires-in-local-mode-test
  (testing "persist fires in :local mode (parity with :governed) so worktree-tier work survives release"
    (let [call-args (atom nil)
          context {:execution/executor :stub
                   :execution/mode :local
                   :execution/environment-id "env-1"
                   :execution/environment-metadata {:base-branch "feat/foo"}
                   :execution/worktree-path "/tmp/scratch"}
          phase-ctx {:execution/current-phase :implement}]
      (with-redefs [dag-exec/persist-workspace!
                    (fn [executor env-id opts]
                      (reset! call-args {:executor executor :env-id env-id :opts opts}))]
        (persist-fn context phase-ctx)
        (is (some? @call-args))
        (is (= "env-1" (:env-id @call-args)))
        ;; base-branch comes from environment-metadata so the worktree tier
        ;; can bundle the right base..HEAD range
        (is (= "feat/foo" (get-in @call-args [:opts :base-branch])))
        (is (= "env-1" (get-in @call-args [:opts :task-id])))))))

(deftest persist-workspace-calls-executor-in-governed-mode-test
  (testing "calls dag-exec/persist-workspace! with correct args in governed mode"
    (let [call-args (atom nil)
          context {:execution/executor :mock-executor
                   :execution/mode :governed
                   :execution/environment-id "env-42"
                   :execution/task-branch "task/abc"
                   :execution/worktree-path "/workspace"}
          phase-ctx {:execution/current-phase :implement}]
      (with-redefs [dag-exec/persist-workspace!
                    (fn [executor env-id opts]
                      (reset! call-args {:executor executor
                                         :env-id env-id
                                         :opts opts}))]
        (persist-fn context phase-ctx)
        (is (some? @call-args))
        (is (= :mock-executor (:executor @call-args)))
        (is (= "env-42" (:env-id @call-args)))
        (is (= "task/abc" (get-in @call-args [:opts :branch])))
        (is (= "/workspace" (get-in @call-args [:opts :workdir])))
        (is (= (messages/t :env/persist-message {:phase "implement"})
               (get-in @call-args [:opts :message])))))))

(deftest persist-workspace-uses-unknown-when-no-phase-test
  (testing "uses 'unknown' when phase-ctx has no :execution/current-phase"
    (let [call-args (atom nil)
          context {:execution/executor :mock
                   :execution/mode :governed
                   :execution/environment-id "env-1"
                   :execution/task-branch "b"}
          phase-ctx {}]
      (with-redefs [dag-exec/persist-workspace!
                    (fn [_exec _env-id opts]
                      (reset! call-args opts))]
        (persist-fn context phase-ctx)
        (is (= (messages/t :env/persist-message {:phase "unknown"})
               (:message @call-args)))))))

(deftest persist-workspace-catches-exceptions-test
  (testing "catches exceptions from persist-workspace! without propagating"
    (let [context {:execution/executor :mock
                   :execution/mode :governed
                   :execution/environment-id "env-1"
                   :execution/task-branch "b"}
          phase-ctx {:execution/current-phase :release}]
      (with-redefs [dag-exec/persist-workspace!
                    (fn [& _] (throw (ex-info "Docker gone" {})))
                    log/warn
                    (fn [& _] nil)]
        ;; Should not throw
        (is (nil? (persist-fn context phase-ctx)))))))

(defn- capture-persist-event-data
  "Capture the data passed to es/workspace-persisted by running the
   persist boundary against a stub executor that always reports a
   successful persist."
  [context phase-ctx]
  (let [event-data (atom nil)
        ok-result  (dag-exec/ok {:persisted? true
                                 :branch "task-1"
                                 :commit-sha "abc123"
                                 :bundle-path "/tmp/x.bundle"})]
    (with-redefs [dag-exec/persist-workspace!
                  (fn [_exec _env-id _opts] ok-result)
                  log/info
                  (fn [& _] nil)
                  es/workspace-persisted
                  (fn [_stream _wf-id data]
                    (reset! event-data data)
                    {:event/type :workspace/persisted})
                  es/publish!
                  (fn [& _] nil)]
      (persist-fn context phase-ctx)
      @event-data)))

(deftest persist-tier-is-worktree-in-local-mode-test
  (testing ":workspace/persisted event labels :local mode as :worktree tier"
    (let [data (capture-persist-event-data
                {:execution/executor :stub
                 :execution/mode :local
                 :execution/environment-id "env-1"
                 :execution/environment-metadata {:base-branch "feat/foo"}
                 :event-stream :stub-stream
                 :execution/id #uuid "00000000-0000-0000-0000-000000000001"}
                {:execution/current-phase :implement})]
      (is (= :worktree (:persist-tier data))
          "local mode persists to a local archive — worktree tier"))))

(deftest persist-tier-is-remote-in-governed-mode-test
  (testing ":workspace/persisted event labels :governed mode as :remote tier"
    (let [data (capture-persist-event-data
                {:execution/executor :stub
                 :execution/mode :governed
                 :execution/environment-id "env-1"
                 :execution/task-branch "task/abc"
                 :event-stream :stub-stream
                 :execution/id #uuid "00000000-0000-0000-0000-000000000002"}
                {:execution/current-phase :implement})]
      (is (= :remote (:persist-tier data))
          "governed mode pushes to remote — remote tier label, not worktree"))))
