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

(ns ai.miniforge.workflow.runner-test
  "Unit tests for the interceptor-based workflow runner.
   Tests that execute real phase pipelines (plan, implement, etc.)
   live in runner-integration-test under project tests."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.interface]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
   [ai.miniforge.workflow.fsm :as workflow-fsm]
   [ai.miniforge.workflow.phase-test-support :as phase-test-support]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.workflow.context :as ctx]))

(defn- with-stubbed-acquire-environment
  "Force `acquire-execution-environment!` to return nil for every test
   in this namespace.

   Why: many tests below call `runner/run-pipeline` without a
   pre-acquired executor. Default :local mode would otherwise acquire
   a real worktree at System/getProperty user.dir, and the workflow-
   tester phases (which call persist-workspace-at-phase-boundary!)
   would commit phase-completion messages into the test runner's own
   git checkout. The 2026-05-16 pre-commit-smoke rollout caught this
   leaking real commits onto active branches; the fix lives once
   here so individual tests don't have to remember the with-redefs
   dance, and so any future test that calls run-pipeline inherits
   the safe default. Tests that genuinely need a real acquired env
   override with their own with-redefs."
  [f]
  (let [acquire-var (requiring-resolve
                      'ai.miniforge.workflow.runner-environment/acquire-execution-environment!)]
    (with-redefs-fn {acquire-var (fn [& _] nil)}
      f)))

;; Compose phase-test-support's loader setup with the acquire-environment
;; stub. clojure.test/use-fixtures REPLACES prior :each fixtures, so both
;; must be registered in a single call.
(use-fixtures :each
  phase-test-support/with-workflow-phase-test-support
  with-stubbed-acquire-environment)

(def test-plan-phase
  phase-test-support/runner-test-plan)

(def test-implement-phase
  phase-test-support/runner-test-implement)

(def test-verify-phase
  phase-test-support/runner-test-verify)

(def test-done-phase
  phase-test-support/runner-test-done)

(defn- with-temp-checkpoint-root
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-runner-checkpoint-test-" (random-uuid)))
               .mkdirs)]
    (try
      (f (.getAbsolutePath root))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete ^java.io.File file))))))

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
      (is (contains? ctx :execution/supervision-runtime))
      (is (contains? ctx :execution/meta-coordinator))
      (is (identical? (:execution/supervision-runtime ctx)
                      (:execution/meta-coordinator ctx)))
      (is (vector? (:execution/artifacts ctx)))
      (is (number? (:execution/started-at ctx))))))

(deftest create-context-preserves-source-root-test
  (testing "create-context preserves :source-root passthrough options"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"}
          source-root "/tmp/miniforge-source-root"
          ctx (ctx/create-context workflow {:task "Test"} {:source-root source-root})]
      (is (= source-root (:source-root ctx))))))

(deftest run-pipeline-persists-final-terminal-snapshot-test
  (testing "terminal failures produced after the loop overwrite the running checkpoint"
    (with-temp-checkpoint-root
      (fn [checkpoint-root]
        (let [workflow {:workflow/id :empty-test
                        :workflow/version "1.0.0"
                        :workflow/pipeline []}
              result (runner/run-pipeline workflow {:task "Test"}
                                          {:checkpoint/root checkpoint-root
                                           :skip-lifecycle-events true})
              checkpoint-data (checkpoint-store/load-checkpoint-data
                               (:execution/id result)
                               {:checkpoint/root checkpoint-root})]
          (is (= :failed (:execution/status result)))
          (is (= :failed (get-in checkpoint-data
                                 [:machine-snapshot :execution/status])))
          (is (seq (get-in checkpoint-data
                           [:machine-snapshot :execution/errors]))))))))

(deftest restore-context-can-reset-terminal-snapshot-test
  (testing "failed checkpoint snapshots can resume a trimmed workflow"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase :implement}
                                        {:phase :verify}]}
          workflow-id (random-uuid)
          failed-started-at 1000
          restored (ctx/restore-context
                    workflow
                    {:task "Test"}
                    {:execution/id workflow-id
                     :execution/input {:task "From snapshot"}
                     :execution/status :failed
                     :execution/fsm-state {:_state :failed}
                     :execution/started-at failed-started-at
                     :execution/ended-at 2000
                     :execution/output {:status :failed}
                     :execution/errors [{:type :boom}]
                     :execution/response-chain {:old :failure}
                     :execution/metrics {:tokens 9
                                         :cost-usd 1.2
                                         :duration-ms 1000}}
                    {:plan {:status :completed}}
                    {:resume-reset-terminal? true})]
      (is (= workflow-id (:execution/id restored)))
      (is (= {:task "From snapshot"} (:execution/input restored)))
      (is (= :running (:execution/status restored)))
      (is (= :implement (:execution/current-phase restored)))
      (is (nil? (:execution/ended-at restored)))
      (is (nil? (:execution/output restored)))
      (is (= [] (:execution/errors restored)))
      (is (= {:tokens 0 :cost-usd 0.0 :duration-ms 0}
             (:execution/metrics restored)))
      (is (not= failed-started-at (:execution/started-at restored)))
      (is (not= {:old :failure} (:execution/response-chain restored)))
      (is (= {:plan {:status :completed}}
             (:execution/phase-results restored))))))

;; ============================================================================
;; Pipeline building tests
;; ============================================================================

(deftest build-pipeline-simple-test
  (testing "build-pipeline creates interceptors from config"
    (let [workflow {:workflow/pipeline
                    [{:phase test-plan-phase}
                     {:phase test-implement-phase}
                     {:phase test-done-phase}]}
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
                    [{:phase/id test-plan-phase}
                     {:phase/id test-implement-phase}
                     {:phase/id test-done-phase}]}
          pipeline (runner/build-pipeline workflow)]
      (is (= 3 (count pipeline))))))

;; ============================================================================
;; Validation tests
;; ============================================================================

(deftest validate-pipeline-valid-test
  (testing "validate-pipeline accepts valid workflow"
    (let [workflow {:workflow/pipeline
                    [{:phase test-plan-phase}
                     {:phase test-done-phase}]}
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
                    :workflow/pipeline [{:phase test-done-phase}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (= :completed (:execution/status result)))
      (is (number? (:execution/ended-at result))))))

(deftest run-pipeline-ensures-supervisory-attachment-test
  (testing "run-pipeline auto-attaches supervisory-state for event-stream callers"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
          stream (es/create-event-stream {:sinks []})]
      (is (false? (supervisory/attached? stream)))
      (let [result (runner/run-pipeline workflow {:task "Test"} {:event-stream stream})
            event-types (map :event/type (es/get-events stream))]
        (is (= :completed (:execution/status result)))
        (is (true? (supervisory/attached? stream)))
        (is (some #{:supervisory/workflow-upserted} event-types))))))

(deftest run-pipeline-persists-machine-snapshot-test
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [workflow {:workflow/id :test
                      :workflow/version "1.0.0"
                      :workflow/pipeline [{:phase test-done-phase}]}
            result (runner/run-pipeline workflow {:task "Test"}
                                        {:checkpoint/root checkpoint-root})
            checkpoint-data (checkpoint-store/load-checkpoint-data
                             (:execution/id result)
                             {:checkpoint/root checkpoint-root})]
        (is (= :completed (:execution/status result)))
        (is (= (:execution/id result)
               (get-in checkpoint-data [:machine-snapshot :execution/id])))
        (is (= [test-done-phase]
               (get-in checkpoint-data [:manifest :workflow/phases-completed])))))))

(deftest run-pipeline-callbacks-test
  (testing "run-pipeline invokes callbacks"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
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
      (is (= [test-done-phase] @started))
      (is (= [test-done-phase] @completed)))))

(deftest run-pipeline-max-phases-test
  (testing "run-pipeline completes a simple workflow within max-phases limit"
    ;; Use :done phase only since other phases require LLM infrastructure
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
          result (runner/run-pipeline workflow {:task "Test"} {:max-phases 50})]
      (is (= :completed (:execution/status result))))))

(deftest run-pipeline-resume-machine-snapshot-test
  (testing "resume-machine-snapshot preserves the original execution id"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
          resume-ctx (ctx/create-context workflow {:task "Test"} {})
          result (runner/run-pipeline workflow
                                      {:task "Ignored"}
                                      {:resume-machine-snapshot
                                       (checkpoint-store/build-machine-snapshot resume-ctx)
                                       :resume-phase-results {}})]
      (is (= :completed (:execution/status result)))
      (is (= (:execution/id resume-ctx) (:execution/id result))))))

(deftest run-pipeline-resume-from-mid-workflow-snapshot-advances-test
  ;; Regression for the dogfood-2026-05-16 silent fast-fail: resume from a
  ;; snapshot whose FSM is actually parked at an intermediate phase must
  ;; advance through the remaining phases and reach `:completed` (not just
  ;; any terminal state). The bug fix lives in PR #896 (loud-fail); this
  ;; test pins the underlying "must actually advance" contract so a
  ;; regression in the FSM/snapshot path can't silently re-introduce
  ;; `:running` returns from completed runs.
  ;;
  ;; The snapshot is constructed by driving the execution machine
  ;; manually from :pending → plan-active → (succeed) → implement-active,
  ;; so the resumed runner has real remaining work to do.
  (testing "snapshot parked at implement-active resumes through implement→verify→done to :completed"
    (let [workflow {:workflow/id :test-multi-phase
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-plan-phase}
                                        {:phase test-implement-phase}
                                        {:phase test-verify-phase}
                                        {:phase test-done-phase}]}
          machine (workflow-fsm/compile-execution-machine workflow)
          ;; Drive the machine from :pending → plan-active → implement-active
          ;; so the snapshot has real remaining work.
          initial-state (workflow-fsm/initialize-execution machine)
          plan-state (workflow-fsm/start-execution machine initial-state)
          fsm-at-implement (workflow-fsm/transition-execution machine plan-state :phase/succeed)
          response-chain (requiring-resolve 'ai.miniforge.response.interface/create)
          mid-snapshot {:execution/id (random-uuid)
                        :execution/workflow-id (:workflow/id workflow)
                        :execution/workflow-version (:workflow/version workflow)
                        :execution/input {:task "Original"}
                        :execution/status :running
                        :execution/fsm-state fsm-at-implement
                        :execution/response-chain (response-chain (:workflow/id workflow))
                        :execution/phase-results {}
                        :execution/artifacts []
                        :execution/errors []
                        :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
                        :execution/started-at (System/currentTimeMillis)}
          seeded-plan-result {:status :success :outcome :success
                              :summary "plan completed before checkpoint"
                              :metrics {:tokens 0 :duration-ms 0}}
          phase-results {test-plan-phase seeded-plan-result}
          resumed (runner/run-pipeline workflow
                                       {:task "Ignored on resume"}
                                       {:resume-machine-snapshot mid-snapshot
                                        :resume-phase-results phase-results})]
      (is (= :completed (:execution/status resumed))
          (str "resume must complete the remaining pipeline, got "
               (pr-str (:execution/status resumed))))
      (is (every? (:execution/phase-results resumed)
                  [test-implement-phase test-verify-phase test-done-phase])
          "implement, verify, and done phases must have phase-results recorded after resume")
      (is (= "plan completed before checkpoint"
             (get-in resumed [:execution/phase-results test-plan-phase :summary]))
          "plan phase was already complete in the snapshot — resume must preserve the seeded result, not re-run it"))))

;; ============================================================================
;; Phase result recording tests
;; ============================================================================

(deftest phase-results-recorded-test
  (testing "phase results are recorded in execution context"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (contains? (:execution/phase-results result) test-done-phase)))))

;; ============================================================================
;; Metrics accumulation tests
;; ============================================================================

(deftest metrics-accumulated-test
  (testing "metrics are accumulated across phases"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
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
                    :workflow/pipeline [{:phase test-done-phase}]}
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
                    :execution/phase-results {test-plan-phase {:phase/status :succeeded}
                                              test-done-phase {:phase/status :succeeded}}
                    :execution/current-phase test-done-phase
                    :execution/status :completed}
          ;; Call extract-output via its var (private fn)
          result (ai.miniforge.workflow.runner/extract-output fake-ctx)
          output (:execution/output result)]
      (is (some? output) ":execution/output should be present")
      (is (= [{:type :file :path "out.txt"}] (:artifacts output)))
      (is (= {test-plan-phase {:phase/status :succeeded}
              test-done-phase {:phase/status :succeeded}}
             (:phase-results output)))
      (is (= {:phase/status :succeeded} (:last-phase-result output)))
      (is (= :completed (:status output))))))

(deftest run-pipeline-returns-execution-output-test
  (testing "run-pipeline returns context with :execution/output populated"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
          result (runner/run-pipeline workflow {:task "Test"} {})]
      (is (= :completed (:execution/status result)))
      (is (some? (:execution/output result))
          ":execution/output should be present after run-pipeline")
      (let [output (:execution/output result)]
        (is (= :completed (:status output)))
        (is (vector? (:artifacts output)))
        (is (map? (:phase-results output)))
        (is (contains? (:phase-results output) test-done-phase))
        (is (some? (:last-phase-result output)))))))

(deftest context-initializes-output-nil-test
  (testing "create-context initializes :execution/output as nil"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"}
          ctx (ctx/create-context workflow {:task "Test"} {})]
      (is (contains? ctx :execution/output))
      (is (nil? (:execution/output ctx))))))

;; ============================================================================
;; Execution environment tests
;; ============================================================================

(deftest run-pipeline-uses-pre-acquired-executor-test
  (testing "pre-acquired executor/environment-id in opts lands in initial context"
    (let [env-id (random-uuid)
          workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
          result (runner/run-pipeline workflow {:task "Test"}
                                       {:executor       ::stub-executor
                                        :environment-id env-id
                                        :worktree-path  "/tmp/worktree"})]
      (is (= :completed (:execution/status result)))
      (is (= env-id (:execution/environment-id result)))
      (is (= "/tmp/worktree" (:execution/worktree-path result))))))

(deftest run-pipeline-threads-acquired-default-branch-test
  (testing "the branch used to acquire a worktree is kept in execution opts"
    (let [acquire-var (resolve 'ai.miniforge.workflow.runner-environment/acquire-execution-environment!)]
      (with-redefs-fn
        {acquire-var (fn [_workflow-id env-config]
                       {:executor ::stub-executor
                        :environment-id "task-branch-test"
                        :worktree-path "/tmp/task-branch-test"
                        :execution-mode :local
                        :environment-metadata {:base-branch (:branch env-config)}})}
        (fn []
          (let [workflow {:workflow/id :test
                          :workflow/version "1.0.0"
                          :workflow/pipeline [{:phase test-done-phase}]}
                result (runner/run-pipeline workflow {:task "Test"} {})]
            (is (= "main" (get-in result [:execution/opts :branch])))
            (is (= {:base-branch "main"}
                   (:execution/environment-metadata result)))))))))

(deftest run-pipeline-threads-resume-workspace-to-acquisition-test
  (testing "resume workspace checkpoint reaches environment acquisition"
    (let [acquire-var (resolve 'ai.miniforge.workflow.runner-environment/acquire-execution-environment!)
          env-config-seen (atom nil)
          resume-workspace {:branch "task-resume"
                            :bundle-path "/tmp/task-resume.bundle"}]
      (with-redefs-fn
        {acquire-var (fn [_workflow-id env-config]
                       (reset! env-config-seen env-config)
                       {:executor ::stub-executor
                        :environment-id "task-resume"
                        :worktree-path "/tmp/task-resume"
                        :execution-mode :local
                        :environment-metadata {:base-branch (:branch env-config)}})}
        (fn []
          (let [workflow {:workflow/id :test
                          :workflow/version "1.0.0"
                          :workflow/pipeline [{:phase test-done-phase}]}
                result (runner/run-pipeline workflow
                                            {:task "Test"}
                                            {:resume-workspace resume-workspace})]
            (is (= resume-workspace (:resume-workspace @env-config-seen)))
            (is (= :completed (:execution/status result)))))))))

(deftest run-pipeline-resume-preserves-checkpoint-metadata-test
  (testing "resume keeps checkpoint metadata when no fresh environment metadata is supplied"
    (let [acquire-var (resolve 'ai.miniforge.workflow.runner-environment/acquire-execution-environment!)
          workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase test-done-phase}]}
          metadata {:base-branch "trunk"}
          resume-ctx (assoc (ctx/create-context workflow {:task "Test"} {})
                            :execution/environment-metadata metadata)]
      (with-redefs-fn
        {acquire-var (fn [& _] nil)}
        (fn []
          (let [result (runner/run-pipeline workflow
                                            {:task "Ignored"}
                                            {:resume-machine-snapshot
                                             (checkpoint-store/build-machine-snapshot resume-ctx)
                                             :resume-phase-results {}})]
            (is (= metadata (:execution/environment-metadata result)))))))))

(deftest run-pipeline-without-executor-skips-env-keys-test
  (testing "when no executor in opts and acquisition yields nil, env keys are absent"
    ;; Force acquisition to return nil for deterministic test isolation.
    ;; Verifies the safe-nil path: acquisition failure → no env keys.
    (let [acquire-var (resolve 'ai.miniforge.workflow.runner-environment/acquire-execution-environment!)]
      (with-redefs-fn
        {acquire-var (fn [& _] nil)}
        (fn []
          (let [workflow {:workflow/id :test
                          :workflow/version "1.0.0"
                          :workflow/pipeline [{:phase test-done-phase}]}
                result (runner/run-pipeline workflow {:task "Test"} {})]
            (is (= :completed (:execution/status result)))
            (is (nil? (:execution/environment-id result)))
            (is (nil? (:execution/worktree-path result)))))))))
