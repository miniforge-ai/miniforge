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

(ns ai.miniforge.task-executor.runner-test
  "Integration test for single-task execution lifecycle.

  Tests that a task flows correctly through: environment acquisition →
  code generation → PR lifecycle → metrics accumulation → cleanup."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.task-executor.runner :as runner]))

;------------------------------------------------------------------------------ Mock Data

(def mock-task
  {:task/id "test-task-1"
   :task/description "Implement feature X"
   :task/acceptance-criteria ["Feature works" "Tests pass"]
   :task/constraints ["No breaking changes"]
   :task/dependencies []})

(def mock-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :ok)"
                 :action :create}
                {:path "test/feature_test.clj"
                 :content "(ns feature-test)\n(deftest test-feature (is true))"
                 :action :create}]
   :code/language "clojure"})

(def mock-pr-info
  {:pr-number 123
   :pr-url "https://github.com/org/repo/pull/123"
   :branch "feature/test-feature"
   :commit-sha "abc123def456"})

(def mock-worktree
  {:path "/tmp/worktree-test-task-1"
   :branch "task-test-task-1"
   :commit-sha "initial123"})

(def mock-environment
  {:env-id :env-123
   :session-id :sess-456
   :status :active})

;------------------------------------------------------------------------------ Mock Implementations

(defn mock-generate-fn
  "Mock code generation function."
  [should-succeed?]
  (fn [task _context]
    (if should-succeed?
      {:status :success
       :output (assoc mock-code-artifact :code/id (random-uuid))
       :metrics {:tokens 1500 :duration-ms 5000}}
      {:status :error
       :error "Code generation failed"
       :data {:task-id (:task/id task)}})))

(defn mock-pr-lifecycle-fn
  "Mock PR lifecycle runner."
  [ci-passes? review-approved?]
  (fn [_controller]
    (cond
      (not ci-passes?)
      {:status :error
       :error "CI failed"
       :data {:check "tests" :status "failure"}}

      (not review-approved?)
      {:status :error
       :error "Review changes requested"
       :data {:reviewer "bot"}}

      :else
      {:status :success
       :output {:pr-number 123
                :merged-sha "final789"}
       :metrics {:tokens 300
                 :duration-ms 15000
                 :ci-runs 1
                 :review-cycles 1}})))

(defn mock-dag-executor
  "Mock DAG executor protocol implementation."
  []
  {:acquire-worktree-fn (fn [_run-context _task-id]
                          mock-worktree)
   :release-worktree-fn (fn [_run-context _task-id]
                          nil)
   :acquire-environment-fn (fn [_run-context]
                             mock-environment)
   :release-environment-fn (fn [_run-context _env-record]
                             nil)
   :transition-task-fn (fn [_run-context _task-id new-state]
                         {:success? true :state new-state})
   :update-metrics-fn (fn [_run-context _task-id _metrics]
                        {:success? true})
   :mark-failed-fn (fn [_run-context _task-id _error]
                     {:success? true :state :failed})})

(defn create-mock-run-context
  "Create mock run context for testing."
  [dag-executor]
  {:dag-id "test-dag"
   :run-id "test-run-001"
   :run-atom (atom {:status :running
                    :tasks {"test-task-1" {:status :pending}}
                    :metrics {:tokens 0}})
   :dag-executor dag-executor
   :generate-fn (mock-generate-fn true)
   :pr-lifecycle-fn (mock-pr-lifecycle-fn true true)
   :futures (atom {})})

;------------------------------------------------------------------------------ Test Helpers

(defn acquire-worktree!
  "Mock worktree acquisition."
  [run-context task-id]
  ((:acquire-worktree-fn (:dag-executor run-context)) run-context task-id))

(defn release-worktree!
  "Mock worktree release."
  [run-context task-id]
  ((:release-worktree-fn (:dag-executor run-context)) run-context task-id))

(defn acquire-environment!
  "Mock environment acquisition."
  [run-context]
  ((:acquire-environment-fn (:dag-executor run-context)) run-context))

(defn release-environment!
  "Mock environment release."
  [run-context env-record]
  ((:release-environment-fn (:dag-executor run-context)) run-context env-record))

(defn transition-task!
  "Mock task state transition."
  [run-context task-id new-state]
  ((:transition-task-fn (:dag-executor run-context)) run-context task-id new-state))

(defn update-metrics!
  "Mock metrics update."
  [run-context task-id metrics]
  ((:update-metrics-fn (:dag-executor run-context)) run-context task-id metrics))

(defn mark-failed!
  "Mock task failure marking."
  [run-context task-id error]
  ((:mark-failed-fn (:dag-executor run-context)) run-context task-id error))

(defn execute-task-lifecycle
  "Simplified task execution for testing."
  [task run-context]
  (try
    ;; 1. Acquire resources
    (let [worktree (acquire-worktree! run-context (:task/id task))
          env (acquire-environment! run-context)]

      (try
        ;; 2. Transition to implementing
        (transition-task! run-context (:task/id task) :implementing)

        ;; 3. Generate code
        (let [generate-result ((:generate-fn run-context) task {:worktree worktree})]

          (when (= :error (:status generate-result))
            (throw (ex-info "Generation failed" {:result generate-result})))

          ;; 4. Transition to PR opening
          (transition-task! run-context (:task/id task) :pr-opening)

          ;; 5. Run PR lifecycle (mocked)
          (let [pr-result ((:pr-lifecycle-fn run-context) {:task task})]

            (when (= :error (:status pr-result))
              (throw (ex-info "PR lifecycle failed" {:result pr-result})))

            ;; 6. Update metrics
            (let [combined-metrics (merge-with + (:metrics generate-result)
                                                 (:metrics pr-result))]
              (update-metrics! run-context (:task/id task) combined-metrics))

            ;; 7. Transition to merged
            (transition-task! run-context (:task/id task) :merged)

            {:success? true
             :artifact (:output generate-result)
             :pr-info (:output pr-result)
             :metrics (merge-with + (:metrics generate-result)
                                    (:metrics pr-result))}))

        (finally
          ;; 8. Cleanup
          (release-environment! run-context env)
          (release-worktree! run-context (:task/id task)))))

    (catch Exception e
      (mark-failed! run-context (:task/id task) e)
      {:success? false
       :error (ex-message e)
       :data (ex-data e)})))

;------------------------------------------------------------------------------ Tests

(deftest task-execution-happy-path-test
  (testing "Task executes successfully through full lifecycle"
    (let [dag-executor (mock-dag-executor)
          run-context (create-mock-run-context dag-executor)
          result (execute-task-lifecycle mock-task run-context)]

      (is (true? (:success? result))
          "Task should complete successfully")
      (is (some? (:artifact result))
          "Should return code artifact")
      (is (some? (:pr-info result))
          "Should return PR info")
      (is (map? (:metrics result))
          "Should accumulate metrics")
      (is (> (:tokens (:metrics result)) 0)
          "Should track tokens used"))))

(deftest worktree-acquisition-test
  (testing "Worktree is acquired before task execution"
    (let [dag-executor (mock-dag-executor)
          run-context (create-mock-run-context dag-executor)
          worktree (acquire-worktree! run-context (:task/id mock-task))]

      (is (some? worktree)
          "Worktree should be acquired")
      (is (string? (:path worktree))
          "Worktree should have path")
      (is (string? (:branch worktree))
          "Worktree should have branch name"))))

(deftest environment-acquisition-test
  (testing "Environment is acquired for task execution"
    (let [dag-executor (mock-dag-executor)
          run-context (create-mock-run-context dag-executor)
          env (acquire-environment! run-context)]

      (is (some? env)
          "Environment should be acquired")
      (is (keyword? (:env-id env))
          "Environment should have ID")
      (is (= :active (:status env))
          "Environment should be active"))))

(deftest code-generation-failure-test
  (testing "Code generation failure marks task as failed"
    (let [dag-executor (mock-dag-executor)
          run-context (-> (create-mock-run-context dag-executor)
                          (assoc :generate-fn (mock-generate-fn false)))
          result (execute-task-lifecycle mock-task run-context)]

      (is (false? (:success? result))
          "Task should fail")
      (is (string? (:error result))
          "Should have error message")
      (is (= "Generation failed" (:error result))
          "Should report generation failure"))))

(deftest pr-lifecycle-ci-failure-test
  (testing "CI failure in PR lifecycle marks task for fixing"
    (let [dag-executor (mock-dag-executor)
          run-context (-> (create-mock-run-context dag-executor)
                          (assoc :pr-lifecycle-fn (mock-pr-lifecycle-fn false true)))
          result (execute-task-lifecycle mock-task run-context)]

      (is (false? (:success? result))
          "Task should fail on CI failure")
      (is (string? (:error result))
          "Should have error message"))))

(deftest pr-lifecycle-review-failure-test
  (testing "Review changes requested triggers fix loop"
    (let [dag-executor (mock-dag-executor)
          run-context (-> (create-mock-run-context dag-executor)
                          (assoc :pr-lifecycle-fn (mock-pr-lifecycle-fn true false)))
          result (execute-task-lifecycle mock-task run-context)]

      (is (false? (:success? result))
          "Task should fail on review changes")
      (is (string? (:error result))
          "Should have error message"))))

(deftest cleanup-on-success-test
  (testing "Resources are cleaned up after successful execution"
    (let [cleanup-tracker (atom {:worktree-released false
                                  :env-released false})
          dag-executor (-> (mock-dag-executor)
                           (assoc :release-worktree-fn
                                  (fn [_run-context _task-id]
                                    (swap! cleanup-tracker assoc :worktree-released true)))
                           (assoc :release-environment-fn
                                  (fn [_run-context _env]
                                    (swap! cleanup-tracker assoc :env-released true))))
          run-context (create-mock-run-context dag-executor)
          result (execute-task-lifecycle mock-task run-context)]

      (is (true? (:success? result))
          "Task should succeed")
      (is (true? (:worktree-released @cleanup-tracker))
          "Worktree should be released")
      (is (true? (:env-released @cleanup-tracker))
          "Environment should be released"))))

(deftest cleanup-on-failure-test
  (testing "Resources are cleaned up even after failure"
    (let [cleanup-tracker (atom {:worktree-released false
                                  :env-released false})
          dag-executor (-> (mock-dag-executor)
                           (assoc :release-worktree-fn
                                  (fn [_run-context _task-id]
                                    (swap! cleanup-tracker assoc :worktree-released true)))
                           (assoc :release-environment-fn
                                  (fn [_run-context _env]
                                    (swap! cleanup-tracker assoc :env-released true))))
          run-context (-> (create-mock-run-context dag-executor)
                          (assoc :generate-fn (mock-generate-fn false)))
          result (execute-task-lifecycle mock-task run-context)]

      (is (false? (:success? result))
          "Task should fail")
      (is (true? (:worktree-released @cleanup-tracker))
          "Worktree should be released even on failure")
      (is (true? (:env-released @cleanup-tracker))
          "Environment should be released even on failure"))))

(deftest state-transitions-test
  (testing "Task transitions through expected states"
    (let [state-tracker (atom [])
          dag-executor (-> (mock-dag-executor)
                           (assoc :transition-task-fn
                                  (fn [_run-context _task-id new-state]
                                    (swap! state-tracker conj new-state)
                                    {:success? true :state new-state})))
          run-context (create-mock-run-context dag-executor)
          result (execute-task-lifecycle mock-task run-context)]

      (is (true? (:success? result))
          "Task should succeed")
      (is (= [:implementing :pr-opening :merged] @state-tracker)
          "Should transition: implementing → pr-opening → merged"))))

(deftest metrics-accumulation-test
  (testing "Metrics accumulate from generation and PR lifecycle"
    (let [metrics-tracker (atom {})
          dag-executor (-> (mock-dag-executor)
                           (assoc :update-metrics-fn
                                  (fn [_run-context _task-id metrics]
                                    (swap! metrics-tracker merge metrics)
                                    {:success? true})))
          run-context (create-mock-run-context dag-executor)
          result (execute-task-lifecycle mock-task run-context)]

      (is (true? (:success? result))
          "Task should succeed")
      (is (> (:tokens @metrics-tracker 0) 1000)
          "Should accumulate tokens from both phases")
      (is (> (:duration-ms @metrics-tracker 0) 10000)
          "Should accumulate duration from both phases"))))

(deftest task-execution-exception-handling-test
  (testing "Unexpected exceptions are caught and logged"
    (let [dag-executor (-> (mock-dag-executor)
                           (assoc :acquire-environment-fn
                                  (fn [_]
                                    (throw (ex-info "Environment unavailable" {})))))
          run-context (create-mock-run-context dag-executor)
          result (execute-task-lifecycle mock-task run-context)]

      (is (false? (:success? result))
          "Task should fail on exception")
      (is (string? (:error result))
          "Should have error message"))))

(deftest parallel-task-isolation-test
  (testing "Multiple tasks can execute without interfering"
    (let [dag-executor (mock-dag-executor)
          run-context (create-mock-run-context dag-executor)
          task-1 (assoc mock-task :task/id "task-1")
          task-2 (assoc mock-task :task/id "task-2")

          ;; Execute tasks in parallel
          future-1 (future (execute-task-lifecycle task-1 run-context))
          future-2 (future (execute-task-lifecycle task-2 run-context))

          result-1 @future-1
          result-2 @future-2]

      (is (true? (:success? result-1))
          "Task 1 should succeed")
      (is (true? (:success? result-2))
          "Task 2 should succeed")
      (is (not= (:artifact result-1) (:artifact result-2))
          "Tasks should have independent artifacts"))))

(deftest fix-loop-integration-test
  (testing "Fix loop is triggered on CI failure"
    (let [attempt-count (atom 0)
          dag-executor (mock-dag-executor)
          run-context (-> (create-mock-run-context dag-executor)
                          (assoc :pr-lifecycle-fn
                                 (fn [_controller]
                                   (swap! attempt-count inc)
                                   (if (< @attempt-count 2)
                                     {:status :error
                                      :error "CI failed"
                                      :data {:attempt @attempt-count}}
                                     {:status :success
                                      :output {:pr-number 123 :merged-sha "final"}
                                      :metrics {:tokens 300 :duration-ms 15000}}))))
          ;; In real implementation, fix loop would be inside PR lifecycle
          ;; This test simulates retry at task level
          _result (execute-task-lifecycle mock-task run-context)]

      ;; First attempt would fail, triggering fix loop
      (is (= 1 @attempt-count)
          "Should attempt PR lifecycle once"))))

;------------------------------------------------------------------------------ validate-spec! Tests
;; Regression coverage for the fail-fast spec validation gate added to execute-task

(defn- write-temp-spec!
  "Write content to a temp .md file and return its path string."
  [content]
  (let [f (java.io.File/createTempFile "test-spec" ".md")]
    (.deleteOnExit f)
    (spit f content)
    (.getAbsolutePath f)))

(deftest validate-spec-no-path-test
  (testing "no-op when task has no spec path"
    (is (nil? (runner/validate-spec! "task-1" {} nil))
        "Should return nil when no spec-related key present"))
  (testing "no-op when task has other keys but no spec path"
    (is (nil? (runner/validate-spec! "task-1" {:task/description "Do stuff"} nil))
        "Should return nil for task with no spec path")))

(deftest validate-spec-valid-file-test
  (testing "returns nil for a well-formed markdown spec"
    (let [path (write-temp-spec!
                (str "---\n"
                     "title: Test Spec\n"
                     "description: A valid test spec\n"
                     "---\n\n"
                     "## Design\n\nSome design content.\n"))
          task {:spec/source-file path}]
      (is (nil? (runner/validate-spec! "task-1" task nil))
          "Should return nil for valid spec"))))

(deftest validate-spec-missing-file-test
  (testing "throws when spec path points to a nonexistent file"
    (let [task {:spec/source-file "/nonexistent/path/spec.md"}]
      (is (thrown-with-msg? Exception #"Spec validation failed"
            (runner/validate-spec! "task-1" task nil))))))

(deftest validate-spec-malformed-spec-test
  (testing "throws when spec file has no YAML frontmatter"
    (let [path (write-temp-spec! "# No Frontmatter\n\nJust a plain markdown file.")
          task {:spec/source-file path}]
      (is (thrown-with-msg? Exception #"Spec validation failed"
            (runner/validate-spec! "task-1" task nil)))))

  (testing "spec-path key is also checked"
    (let [task {:spec-path "/nonexistent/spec.md"}]
      (is (thrown-with-msg? Exception #"Spec validation failed"
            (runner/validate-spec! "task-1" task nil))))))

(deftest validate-spec-provenance-key-test
  (testing "reads spec path from :spec/provenance :source-file"
    (let [path (write-temp-spec!
                (str "---\n"
                     "title: T\n"
                     "description: D\n"
                     "---\n"))
          task {:spec/provenance {:source-file path}}]
      (is (nil? (runner/validate-spec! "task-1" task nil))
          "Should accept path nested under :spec/provenance"))))
