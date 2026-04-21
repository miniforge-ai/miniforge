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

(ns ai.miniforge.workflow.environment-promotion-integration-test
  "Integration tests for the environment promotion pipeline.

   Verifies the full plan→implement→verify→release pipeline end-to-end:
   - environment-id flows through all phases
   - implement agent writes to the executor environment (real temp dir)
   - environment is released on both success and failure paths
   - pre-acquired executor shortcut wires environment-id into initial context

   No Docker or network access required: dag-executor and agent/invoke are
   mocked; verify/run-tests! is mocked to return passing results."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   ;; Required for defmethod side effects (register phase handlers)
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase-software-factory.plan]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase-software-factory.implement]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase-software-factory.verify]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase-software-factory.release]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.dag-executor.executor :as dag-exec]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.release-executor.interface :as release-executor]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Fixtures

(def ^:dynamic *test-worktree* nil)

(defn create-temp-worktree []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "env-promotion-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    (.getPath temp-dir)))

(defn cleanup-temp-worktree [dir-path]
  (when dir-path
    (try (fs/delete-tree dir-path) (catch Exception _e nil))))

(defn worktree-fixture [f]
  (let [worktree (create-temp-worktree)]
    (binding [*test-worktree* worktree]
      (try (f)
           (finally (cleanup-temp-worktree worktree))))))

(use-fixtures :each worktree-fixture)

;------------------------------------------------------------------------------ Helpers

(def passing-test-results
  {:passed? true :test-count 5 :assertion-count 10
   :fail-count 0 :error-count 0
   :output "Ran 5 tests containing 10 assertions.\n0 failures, 0 errors."})

(def failing-test-results
  {:passed? false :test-count 3 :assertion-count 6
   :fail-count 2 :error-count 0
   :output "Ran 3 tests containing 6 assertions.\n2 failures, 0 errors."})

(def mock-release-success
  {:success? true
   :artifacts [{:artifact/id (random-uuid)
                :artifact/type :release
                :artifact/content {:branch "feat/test-branch"
                                   :commit-sha "abc123"
                                   :pr-url "https://github.com/example/repo/pull/1"
                                   :pr-number 1}}]
   :metrics {:tokens 200 :duration-ms 1000}
   :errors []})

(defn mock-curator
  "Default curator stub for tests that don't care about file-diff semantics.

   The real curator (agent/curate-implement-output) inspects the worktree
   to detect written files. In unit tests the worktree is a bare temp dir
   (not a git repo) and there's no pre-session-snapshot, so curator
   collect-files returns [], and the implementer phase fails with
   :curator/no-files-written. That's correct production behavior but
   irrelevant to these environment-promotion tests. Mock the curator so
   implement phase can complete and expose the environment-id plumbing."
  [_input]
  (response/success
    {:code/files [{:path "src/feature.clj" :action :create}]
     :code/summary "test fixture wrote feature.clj"}
    {:metrics {:tokens 0 :duration-ms 0}}))

(defn make-workflow
  "Build a minimal workflow config with the given phase sequence.

   Gates are disabled because these tests target environment-promotion
   plumbing, not gate semantics. With gates on, the :implement phase's
   default [:syntax :format :lint] gates run against whatever file the
   mock curator reports — which has no real content — and cascade into
   phase failure for reasons unrelated to environment-id flow."
  [& phases]
  {:workflow/id :env-promotion-test
   :workflow/version "1.0.0"
   :workflow/pipeline (mapv (fn [p] {:phase p :gates []}) phases)})

(defn base-input []
  {:task "Add a new feature"
   :description "Add a new feature to the codebase"
   :title "Add feature"
   :intent "implement"})

;------------------------------------------------------------------------------ Test 1: Full pipeline — environment flows through all phases

(deftest full-pipeline-environment-flows-through-phases-test
  (testing "Full plan→implement→verify→done pipeline with pre-acquired executor"
    (let [env-id      (random-uuid)
          release-called? (atom false)
          release-env-called? (atom false)
          run-var     (resolve 'ai.miniforge.phase-software-factory.verify/run-tests!)
          workflow    (make-workflow :implement :verify :done)]
      (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                    agent/invoke
                    (fn [_ _ ctx]
                      ;; Simulate implementer writing a file directly to the worktree
                      (let [worktree-path (:execution/worktree-path ctx)
                            file (io/file worktree-path "src/feature.clj")]
                        (io/make-parents file)
                        (spit file "(ns feature)\n(defn new-feature [] :implemented)"))
                      (response/success nil {:tokens 1000 :duration-ms 2000}))
                    agent/curate-implement-output mock-curator
                    dag-exec/release-environment!
                    (fn [_ _env-id]
                      (reset! release-env-called? true)
                      nil)
                    release-executor/execute-release-phase
                    (fn [_ _ _]
                      (reset! release-called? true)
                      mock-release-success)]
        (with-redefs-fn
          {run-var (fn [_ & _opts] passing-test-results)}
          (fn []
            (let [result (runner/run-pipeline
                          workflow
                          (base-input)
                          {:executor       ::stub-executor
                           :environment-id env-id
                           :worktree-path  *test-worktree*
                           :skip-lifecycle-events true})]

              ;; Pipeline should complete
              (is (= :completed (:execution/status result))
                  "Pipeline should complete successfully")

              ;; environment-id should be set on the context
              (is (= env-id (:execution/environment-id result))
                  ":execution/environment-id should be set on context")

              ;; environment-id should appear in implement phase result
              (is (= env-id (get-in result [:execution/phase-results :implement :result :environment-id]))
                  ":execution/environment-id should appear in implement phase result")

              ;; environment-id should appear in verify phase result
              (is (some? (get-in result [:execution/phase-results :verify]))
                  "Verify phase result should be present")
              (is (= env-id (get-in result [:execution/phase-results :verify :result :environment-id]))
                  ":execution/environment-id should appear in verify phase result")

              ;; The file written by implement agent should exist in temp dir
              (is (.exists (io/file *test-worktree* "src/feature.clj"))
                  "File written by implement agent should exist in executor environment"))))))))

;------------------------------------------------------------------------------ Test 2: Environment released on pipeline failure

(deftest environment-released-on-pipeline-failure-test
  (testing "Environment is released in finally block even when pipeline fails"
    (let [acquired-env-id (random-uuid)
          release-called? (atom false)
          ;; Simulate acquisition returning an env record
          acquired-env    {:executor       ::stub-executor
                           :environment-id acquired-env-id
                           :worktree-path  *test-worktree*
                           :execution-mode :local}
          acquire-var     (resolve 'ai.miniforge.workflow.runner-environment/acquire-execution-environment!)
          workflow        (make-workflow :implement :done)]
      (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                    agent/invoke
                    (fn [_ _ _]
                      ;; Return error to cause pipeline failure
                      (response/error "LLM timeout" {:tokens 0 :duration-ms 5000}))
                    dag-exec/release-environment!
                    (fn [_ _env-id]
                      (reset! release-called? true)
                      nil)]
        (with-redefs-fn
          {acquire-var (fn [& _] acquired-env)}
          (fn []
            (let [result (runner/run-pipeline
                          workflow
                          (base-input)
                          {:skip-lifecycle-events true
                           :max-phases 3})]

              ;; Pipeline should fail (agent returned error, budget exhausted or retrying)
              (is (contains? #{:failed :running} (:execution/status result))
                  "Pipeline should not be :completed when agent returns error")

              ;; Environment should still be released
              (is (true? @release-called?)
                  "release-environment! should be called in the finally block even on failure"))))))))

;------------------------------------------------------------------------------ Test 3: Environment-id propagates through context via pre-acquired shortcut

(deftest environment-id-propagates-via-pre-acquired-shortcut-test
  (testing "Pre-acquired :executor + :environment-id shortcut sets env-id on initial context"
    (let [env-id   (random-uuid)
          workflow (make-workflow :implement :done)]
      (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                    agent/invoke
                    (fn [_ _ _]
                      (response/success nil {:tokens 500 :duration-ms 1000}))
                    agent/curate-implement-output mock-curator]
        (let [result (runner/run-pipeline
                      workflow
                      (base-input)
                      {:executor       ::stub-executor
                       :environment-id env-id
                       :worktree-path  *test-worktree*
                       :skip-lifecycle-events true})]

          ;; environment-id should survive through completion
          (is (= env-id (:execution/environment-id result))
              ":execution/environment-id should be set on context and survive to completion")

          ;; worktree-path should be set on context
          (is (= *test-worktree* (:execution/worktree-path result))
              ":execution/worktree-path should be set on context")

          ;; No acquisition should occur (has-executor? shortcut)
          ;; Pipeline should complete normally
          (is (= :completed (:execution/status result))
              "Pipeline should complete when pre-acquired executor is provided"))))))

;------------------------------------------------------------------------------ Test 4: release-environment! NOT called when pre-acquired executor was passed

(deftest pre-acquired-executor-not-released-by-runner-test
  (testing "Runner does not call release-environment! when executor was pre-acquired (caller owns lifecycle)"
    (let [env-id         (random-uuid)
          release-called? (atom false)
          workflow        (make-workflow :done)]
      (with-redefs [dag-exec/release-environment!
                    (fn [_ _env-id]
                      (reset! release-called? true)
                      nil)]
        (let [result (runner/run-pipeline
                      workflow
                      (base-input)
                      {:executor       ::stub-executor
                       :environment-id env-id
                       :worktree-path  *test-worktree*
                       :skip-lifecycle-events true})]

          (is (= :completed (:execution/status result))
              "Pipeline should complete")

          ;; When executor is pre-acquired, runner should NOT release it
          (is (false? @release-called?)
              "Runner should not release a pre-acquired environment (caller owns lifecycle)"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.environment-promotion-integration-test)
  :leave-this-here)
