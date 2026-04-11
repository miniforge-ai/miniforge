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

(ns ai.miniforge.phase.handoff-test
  "Integration test for artifact handoff between workflow phases.

  This test validates that artifacts flow correctly through the phase pipeline:
  plan → implement → verify → release

  Mocks all agent invocations to focus purely on the handoff plumbing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.release-executor.interface :as release-executor]
   [babashka.fs :as fs]
   [babashka.process :as process]))

(defn with-mocked-test-runner
  "Run body-fn with run-tests! mocked to prevent subprocess spawning."
  [body-fn]
  (let [run-var (resolve 'ai.miniforge.phase.verify/run-tests!)]
    (with-redefs-fn
      {run-var (fn [& _args] {:passed? true :test-count 1 :fail-count 0 :error-count 0})}
      body-fn)))

;------------------------------------------------------------------------------ Mock Data

(def mock-plan-result
  "Mock plan output from plan phase."
  {:plan/id (random-uuid)
   :plan/tasks [{:task "Implement feature X"
                 :file "src/feature.clj"
                 :approach "Add new function"}]
   :plan/estimated-effort :medium})

(def mock-code-artifact
  "Mock code artifact from implement phase."
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :implemented)"
                 :action :create}
                {:path "test/feature_test.clj"
                 :content "(ns feature-test)\n(deftest test-feature (is true))"
                 :action :create}]
   :code/language "clojure"})

(def mock-test-result
  "Mock test result from verify phase."
  {:test/id (random-uuid)
   :test/files [{:path "test/feature_test.clj"
                 :content "(ns feature-test)\n(deftest test-feature (is true))"}]
   :test/type :unit
   :test/coverage {:lines 90.0 :branches 85.0 :functions 95.0}
   :test/framework "clojure.test"
   :test/assertions-count 15
   :test/cases-count 8
   :test/passed? true})

;------------------------------------------------------------------------------ Mock Agents

(defn mock-agent-invoke
  "Mock agent invocation that returns success with canned responses.

  Validates that the agent receives the correct artifacts from previous phases."
  [agent-type expected-artifact-key]
  (fn [_agent task _ctx]
    (case agent-type
      :planner
      (response/success mock-plan-result {:tokens 500 :duration-ms 1000})

      :implementer
      (do
        ;; Verify implementer receives the plan
        (when expected-artifact-key
          (is (some? (get task expected-artifact-key))
              (str "Implementer should receive " expected-artifact-key)))
        (response/success mock-code-artifact {:tokens 1500 :duration-ms 3000}))

      :tester
      (do
        ;; Verify tester receives code artifact
        (is (some? (:code task))
            "Tester should receive :code from implement phase")
        (is (= (:code/id mock-code-artifact) (:code/id (:code task)))
            "Tester should receive the exact code artifact from implement")
        (response/success mock-test-result {:tokens 800 :duration-ms 2000}))

      :reviewer
      (do
        ;; Verify reviewer receives code artifact
        (is (some? (:code task))
            "Reviewer should receive :code from implement phase")
        (response/success {:approved? true
                          :comments ["LGTM"]}
                         {:tokens 300 :duration-ms 500}))

      ;; Default
      (response/success {:result :ok} {:tokens 0 :duration-ms 0}))))

;------------------------------------------------------------------------------ Test Helpers

(defn create-base-context
  "Create minimal execution context for phase testing."
  []
  {:execution/id (random-uuid)
   :execution/input {:description "Test feature implementation"
                     :title "Add feature X"
                     :intent "Implement new feature"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {}
   :execution/worktree-path (System/getProperty "user.dir")
   :execution/environment-id "test-env-001"})

(defn with-test-worktree
  "Execute body-fn with a temporary git repo containing a dirty file.
   Passes the worktree path to body-fn."
  [body-fn]
  (let [dir (fs/create-temp-dir {:prefix "handoff-test-"})]
    (process/shell {:dir (str dir)} "git init")
    (process/shell {:dir (str dir)} "git config user.email" "test@example.com")
    (process/shell {:dir (str dir)} "git config user.name" "Test User")
    (process/shell {:dir (str dir)} "git config commit.gpgsign" "false")
    (spit (str (fs/path dir "README.md")) "# Test")
    (process/shell {:dir (str dir)} "git add README.md")
    (process/shell {:dir (str dir)} "git commit -m" "initial")
    ;; Add a dirty file so git-dirty-files finds something
    (let [src-dir (io/file (str dir) "src")]
      (.mkdirs src-dir)
      (spit (str (fs/path dir "src" "feature.clj")) "(ns feature)\n(defn hello [] :world)"))
    (try
      (body-fn (str dir))
      (finally
        (fs/delete-tree dir)))))

(defn execute-phase-enter
  "Execute a phase's enter function and return updated context.

  Simulates the workflow runner's phase execution."
  [phase-name ctx]
  (let [interceptor (phase/get-phase-interceptor {:phase phase-name})
        enter-fn (:enter interceptor)]
    (enter-fn ctx)))

(defn execute-phase-leave
  "Execute a phase's leave function and store result.

  Simulates the workflow runner's phase completion and result storage."
  [phase-name ctx]
  (let [interceptor (phase/get-phase-interceptor {:phase phase-name})
        leave-fn (:leave interceptor)
        updated-ctx (leave-fn ctx)]
    ;; Store entire phase map in execution context (mimics workflow runner)
    ;; The real workflow runner stores the full :phase map via extract-phase-result
    (assoc-in updated-ctx [:execution/phase-results phase-name]
              (:phase updated-ctx))))

;------------------------------------------------------------------------------ Tests

(deftest plan-to-implement-handoff-test
  (testing "Plan phase result is correctly handed to implement phase"
    (with-redefs [agent/create-planner (fn [_] {:type :mock-planner})
                  agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (mock-agent-invoke :planner nil)]

      (let [;; Execute plan phase
            ctx1 (execute-phase-enter :plan (create-base-context))
            _    (is (= :success (get-in ctx1 [:phase :result :status]))
                     "Plan phase should succeed")
            ctx2 (execute-phase-leave :plan ctx1)

            plan-phase-result (get-in ctx2 [:execution/phase-results :plan])
            plan-result (get-in plan-phase-result [:result :output])
            _    (is (some? plan-phase-result)
                     "Plan phase result should be stored in execution context")
            _    (is (some? plan-result)
                     "Plan output should be present in phase result")
            _    (is (= (:plan/id mock-plan-result) (:plan/id plan-result))
                     "Stored plan should match mock data")]

        ;; Execute implement phase - should read plan from execution context
        (with-redefs [agent/invoke (mock-agent-invoke :implementer :task/plan)]
          (let [ctx3 (execute-phase-enter :implement ctx2)]
            (is (= :success (get-in ctx3 [:phase :result :status]))
                "Implement phase should succeed")))))))

(deftest implement-to-verify-handoff-test
  (testing "Implement phase result is available for verify phase"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (mock-agent-invoke :implementer nil)]

      (let [;; Execute implement phase
            ctx1 (execute-phase-enter :implement (create-base-context))
            _    (is (= :success (get-in ctx1 [:phase :result :status]))
                     "Implement phase should succeed")
            ctx2 (execute-phase-leave :implement ctx1)

            impl-phase-result (get-in ctx2 [:execution/phase-results :implement])
            ;; In the environment model, implement result carries provenance
            ;; metadata (:status, :environment-id, :summary) rather than
            ;; serialized :output with :code/files.
            impl-result (get-in impl-phase-result [:result])
            _    (is (some? impl-phase-result)
                     "Implement phase result should be stored in execution context")
            _    (is (= :success (:status impl-result))
                     "Implement result should have :success status")]

        ;; Execute verify phase - runs tests directly in the environment
        (with-mocked-test-runner
          (fn []
            (let [ctx3 (execute-phase-enter :verify ctx2)]
              (is (= :success (get-in ctx3 [:phase :result :status]))
                  "Verify phase should succeed")
              (is (some? (get-in ctx3 [:phase :result :summary]))
                  "Verify phase should produce test summary"))))))))


(deftest implement-to-release-handoff-test
  (testing "Implement phase result enables release phase to proceed"
    (with-test-worktree
      (fn [worktree-path]
        (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                      agent/invoke (mock-agent-invoke :implementer nil)
                      ;; Mock release-executor to avoid real git operations
                      release-executor/execute-release-phase
                      (fn [workflow-state _exec-context _opts]
                        ;; Verify workflow-state contains artifacts discovered from worktree
                        (is (some? (:workflow/artifacts workflow-state))
                            "Workflow state should contain artifacts")
                        (is (pos? (count (:workflow/artifacts workflow-state)))
                            "Should have at least one artifact")
                        ;; In the environment model, artifacts contain files from git diff
                        (let [artifact (first (:workflow/artifacts workflow-state))
                              content (:artifact/content artifact)]
                          (is (seq (:code/files content))
                              "Release should receive code files discovered from worktree"))
                        ;; Return success
                        {:success? true
                         :artifacts [{:artifact/id (random-uuid)
                                      :artifact/type :release
                                      :artifact/content {:branch "feature/test"
                                                         :commit-sha "abc123"
                                                         :files-written 2}}]
                         :metrics {:tokens 100 :duration-ms 500}})]

          (let [;; Create context with real worktree
                base-ctx (assoc (create-base-context)
                                :execution/worktree-path worktree-path)
                ;; Execute implement phase
                ctx1 (execute-phase-enter :implement base-ctx)
                ctx2 (execute-phase-leave :implement ctx1)

                ;; Execute release phase - discovers files from worktree
                ctx3 (execute-phase-enter :release ctx2)]
            (is (= :success (get-in ctx3 [:phase :result :status]))
                "Release phase should succeed")
            (is (some? (get-in ctx3 [:phase :result :output]))
                "Release phase should produce release artifact")))))))

(deftest full-pipeline-handoff-test
  (testing "Artifacts flow correctly through entire pipeline: plan → implement → verify → release"
    (with-test-worktree
      (fn [worktree-path]
        (with-redefs [agent/create-planner (fn [_] {:type :mock-planner})
                      agent/create-implementer (fn [_] {:type :mock-implementer})
                      agent/invoke (fn [agent task ctx]
                                     (cond
                                       (= (:type agent) :mock-planner)
                                       ((mock-agent-invoke :planner nil) agent task ctx)

                                       (= (:type agent) :mock-implementer)
                                       ((mock-agent-invoke :implementer :task/plan) agent task ctx)

                                       :else
                                       (response/success {:result :ok} {:tokens 0 :duration-ms 0})))
                      ;; Mock release-executor
                      release-executor/execute-release-phase
                      (fn [workflow-state _exec-context _opts]
                        (is (some? (:workflow/artifacts workflow-state))
                            "Release should receive artifacts")
                        {:success? true
                         :artifacts [{:artifact/id (random-uuid)
                                      :artifact/type :release
                                      :artifact/content {:files-written 2}}]
                         :metrics {:tokens 100 :duration-ms 500}})]

          ;; Mock verify phase test runner to prevent subprocess spawning
          (with-mocked-test-runner
            (fn []
              (let [base-ctx (assoc (create-base-context)
                                    :execution/worktree-path worktree-path)
                    ;; 1. Plan phase
                    ctx1 (execute-phase-enter :plan base-ctx)
                    _    (is (= :success (get-in ctx1 [:phase :result :status])))
                    ctx2 (execute-phase-leave :plan ctx1)
                    _    (is (some? (get-in ctx2 [:execution/phase-results :plan]))
                             "Plan result stored")

                    ;; 2. Implement phase (reads plan)
                    ctx3 (execute-phase-enter :implement ctx2)
                    _    (is (= :success (get-in ctx3 [:phase :result :status])))
                    ctx4 (execute-phase-leave :implement ctx3)
                    _    (is (some? (get-in ctx4 [:execution/phase-results :implement]))
                             "Implement result stored")

                    ;; 3. Verify phase (runs tests in environment)
                    ctx5 (execute-phase-enter :verify ctx4)
                    _    (is (= :success (get-in ctx5 [:phase :result :status])))
                    ctx6 (execute-phase-leave :verify ctx5)

                    ;; 4. Release phase (discovers files from worktree)
                    ctx7 (execute-phase-enter :release ctx6)
                    _    (is (= :success (get-in ctx7 [:phase :result :status])))
                    ctx8 (execute-phase-leave :release ctx7)]

                ;; Verify final state
                (is (= [:plan :implement :verify :release]
                       (get-in ctx8 [:execution :phases-completed]))
                    "All phases should complete in order")

                (is (pos? (get-in ctx8 [:execution/metrics :tokens]))
                    "Should accumulate token metrics from all phases")))))))))

(deftest handoff-with-missing-artifact-test
  (testing "Verify phase fails fast when no execution environment is available"
    ;; Create context WITHOUT execution environment — verify should throw
    (let [ctx (dissoc (create-base-context) :execution/environment-id)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Verify phase has no execution environment"
                            (execute-phase-enter :verify ctx))
          "Verify phase should throw when no execution environment is available"))))
