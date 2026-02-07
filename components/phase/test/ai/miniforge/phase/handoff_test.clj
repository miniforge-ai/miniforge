(ns ai.miniforge.phase.handoff-test
  "Integration test for artifact handoff between workflow phases.

  This test validates that artifacts flow correctly through the phase pipeline:
  plan → implement → verify → release

  Mocks all agent invocations to focus purely on the handoff plumbing."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.phase.plan :as plan]
   [ai.miniforge.phase.implement :as implement]
   [ai.miniforge.phase.verify :as verify]
   [ai.miniforge.phase.release :as release]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.release-executor.interface :as release-executor]))

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
   :execution/phase-results {}})

(defn execute-phase-enter
  "Execute a phase's enter function and return updated context.

  Simulates the workflow runner's phase execution."
  [phase-name ctx]
  (let [interceptor (registry/get-phase-interceptor {:phase phase-name})
        enter-fn (:enter interceptor)]
    (enter-fn ctx)))

(defn execute-phase-leave
  "Execute a phase's leave function and store result.

  Simulates the workflow runner's phase completion and result storage."
  [phase-name ctx]
  (let [interceptor (registry/get-phase-interceptor {:phase phase-name})
        leave-fn (:leave interceptor)
        result (get-in ctx [:phase :result])
        updated-ctx (leave-fn ctx)]
    ;; Store phase result in execution context (mimics workflow runner)
    (assoc-in updated-ctx [:execution/phase-results phase-name]
              (:output result))))

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

            plan-result (get-in ctx2 [:execution/phase-results :plan])
            _    (is (some? plan-result)
                     "Plan result should be stored in execution context")
            _    (is (= (:plan/id mock-plan-result) (:plan/id plan-result))
                     "Stored plan should match mock data")]

        ;; Execute implement phase - should read plan from execution context
        (with-redefs [agent/invoke (mock-agent-invoke :implementer :task/plan)]
          (let [ctx3 (execute-phase-enter :implement ctx2)]
            (is (= :success (get-in ctx3 [:phase :result :status]))
                "Implement phase should succeed")
            (is (some? (get-in ctx3 [:phase :result :output]))
                "Implement phase should produce code artifact")))))))

(deftest implement-to-verify-handoff-test
  (testing "Implement phase artifact is correctly handed to verify phase"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (mock-agent-invoke :implementer nil)]

      (let [;; Execute implement phase
            ctx1 (execute-phase-enter :implement (create-base-context))
            _    (is (= :success (get-in ctx1 [:phase :result :status]))
                     "Implement phase should succeed")
            ctx2 (execute-phase-leave :implement ctx1)

            impl-result (get-in ctx2 [:execution/phase-results :implement])
            _    (is (some? impl-result)
                     "Implement result should be stored in execution context")
            _    (is (= (:code/id mock-code-artifact) (:code/id impl-result))
                     "Stored code artifact should match mock data")]

        ;; Execute verify phase - should read code from execution context
        (with-redefs [agent/invoke (mock-agent-invoke :tester nil)]
          (let [ctx3 (execute-phase-enter :verify ctx2)]
            (is (= :success (get-in ctx3 [:phase :result :status]))
                "Verify phase should succeed")
            (is (some? (get-in ctx3 [:phase :result :output]))
                "Verify phase should produce test result")))))))

(deftest implement-to-release-handoff-test
  (testing "Implement phase artifact is correctly handed to release phase"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (mock-agent-invoke :implementer nil)
                  ;; Mock release-executor to avoid file system operations
                  release-executor/execute-release-phase
                  (fn [workflow-state _exec-context _opts]
                    ;; Verify workflow-state contains artifacts from implement
                    (is (some? (:workflow/artifacts workflow-state))
                        "Workflow state should contain artifacts")
                    (is (pos? (count (:workflow/artifacts workflow-state)))
                        "Should have at least one artifact")
                    (let [artifact (first (:workflow/artifacts workflow-state))
                          content (:artifact/content artifact)]
                      (is (= (:code/id mock-code-artifact) (:code/id content))
                          "Release should receive implement phase code artifact"))
                    ;; Return success
                    {:success? true
                     :artifacts [{:artifact/id (random-uuid)
                                  :artifact/type :release
                                  :artifact/content {:branch "feature/test"
                                                     :commit-sha "abc123"
                                                     :files-written 2}}]
                     :metrics {:tokens 100 :duration-ms 500}})]

      (let [;; Execute implement phase
            ctx1 (execute-phase-enter :implement (create-base-context))
            ctx2 (execute-phase-leave :implement ctx1)

            ;; Add worktree-path for release executor
            ctx2-with-path (assoc ctx2 :worktree-path "/tmp/test-repo")]

        ;; Execute release phase - should read code from execution context
        (let [ctx3 (execute-phase-enter :release ctx2-with-path)]
          (is (= :success (get-in ctx3 [:phase :result :status]))
              "Release phase should succeed")
          (is (some? (get-in ctx3 [:phase :result :output]))
              "Release phase should produce release artifact"))))))

(deftest full-pipeline-handoff-test
  (testing "Artifacts flow correctly through entire pipeline: plan → implement → verify → release"
    (with-redefs [agent/create-planner (fn [_] {:type :mock-planner})
                  agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (fn [agent task ctx]
                                 (cond
                                   (= (:type agent) :mock-planner)
                                   ((mock-agent-invoke :planner nil) agent task ctx)

                                   (= (:type agent) :mock-implementer)
                                   ((mock-agent-invoke :implementer :task/plan) agent task ctx)

                                   (= (:type agent) :mock-tester)
                                   ((mock-agent-invoke :tester nil) agent task ctx)

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

      (let [;; 1. Plan phase
            ctx1 (execute-phase-enter :plan (create-base-context))
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

            ;; 3. Verify phase (reads implement)
            ctx5 (execute-phase-enter :verify ctx4)
            _    (is (= :success (get-in ctx5 [:phase :result :status])))
            ctx6 (execute-phase-leave :verify ctx5)

            ;; 4. Release phase (reads implement)
            ctx6-with-path (assoc ctx6 :worktree-path "/tmp/test-repo")
            ctx7 (execute-phase-enter :release ctx6-with-path)
            _    (is (= :success (get-in ctx7 [:phase :result :status])))
            ctx8 (execute-phase-leave :release ctx7)]

        ;; Verify final state
        (is (= [:plan :implement :verify :release]
               (get-in ctx8 [:execution :phases-completed]))
            "All phases should complete in order")

        (is (pos? (get-in ctx8 [:execution/metrics :tokens]))
            "Should accumulate token metrics from all phases")))))

(deftest handoff-with-missing-artifact-test
  (testing "Verify phase handles missing implement artifact"
    (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                  ;; Mock that doesn't assert on missing artifacts
                  agent/invoke (fn [_agent task _ctx]
                                 ;; Just return success without assertions
                                 (response/success {:result :ok} {:tokens 0 :duration-ms 0}))]

      ;; Create context WITHOUT implement phase result
      (let [ctx (create-base-context)
            result (execute-phase-enter :verify ctx)]

        ;; Verify phase should execute even without implement result
        ;; (it has fallback to read from input if implement is missing)
        (is (some? result)
            "Verify phase should return a result")
        (is (some? (get-in result [:phase :result]))
            "Phase result should be present")))))
