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
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.dag-executor.executor :as dag-exec]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.phase-test-support :as phase-test-support]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Fixtures

(def ^:private env-promotion-implement
  :env-promotion-test-implement)

(def ^:private env-promotion-verify
  :env-promotion-test-verify)

(def ^:private env-promotion-done
  phase-test-support/runner-test-done)

(def ^:dynamic *test-worktree* nil)

(def phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

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

(defn phase-loader-fixture [f]
  (phase/reset-phase-loader!)
  (try
    (binding [loader/phase-loader-config-resource phase-test-config-resource]
      (f))
    (finally
      (phase/reset-phase-loader!))))

(use-fixtures :each worktree-fixture phase-loader-fixture)

;------------------------------------------------------------------------------ Helpers

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

(def ^:private env-promotion-phase-defaults
  {:agent :workflow-tester
   :gates []
   :budget {:tokens 1
            :iterations 1
            :time-seconds 1}})

(defn- register-env-promotion-phase!
  [phase-name]
  (registry/register-phase-defaults!
   phase-name
   (assoc env-promotion-phase-defaults :phase phase-name)))

(defn- implement-phase-result
  [ctx]
  (let [agent-result (agent/invoke (agent/create-implementer nil) nil ctx)
        curated-result (when (response/success? agent-result)
                         (agent/curate-implement-output {:context ctx
                                                         :llm-response agent-result}))]
    (if (response/success? agent-result)
      {:status :completed
       :result {:status :success
                :environment-id (:execution/environment-id ctx)
                :output curated-result}
       :metrics (:metrics agent-result)}
      {:status :failed
       :result agent-result
       :metrics (:metrics agent-result)})))

(defn- verify-phase-result
  [ctx]
  {:status :completed
   :result {:status :success
            :environment-id (:execution/environment-id ctx)
            :output {:tests-passed? true}}})

(defmethod registry/get-phase-interceptor env-promotion-implement
  [config]
  {:name ::env-promotion-implement
   :config (registry/merge-with-defaults config)
   :enter (fn [ctx] (assoc ctx :phase (implement-phase-result ctx)))
   :leave identity
   :error (fn [ctx _ex] ctx)})

(defmethod registry/get-phase-interceptor env-promotion-verify
  [config]
  {:name ::env-promotion-verify
   :config (registry/merge-with-defaults config)
   :enter (fn [ctx] (assoc ctx :phase (verify-phase-result ctx)))
   :leave identity
   :error (fn [ctx _ex] ctx)})

(register-env-promotion-phase! env-promotion-implement)
(register-env-promotion-phase! env-promotion-verify)

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
          release-env-called? (atom false)
          workflow    (make-workflow env-promotion-implement
                                     env-promotion-verify
                                     env-promotion-done)]
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
                      nil)]
        (let [result (runner/run-pipeline
                      workflow
                      (base-input)
                      {:executor       ::stub-executor
                       :environment-id env-id
                       :worktree-path  *test-worktree*
                       :skip-lifecycle-events true})]

          (is (= :completed (:execution/status result))
              "Pipeline should complete successfully")
          (is (= env-id (:execution/environment-id result))
              ":execution/environment-id should be set on context")
          (is (= env-id
                 (get-in result
                         [:execution/phase-results env-promotion-implement :result :environment-id]))
              ":execution/environment-id should appear in implement phase result")
          (is (some? (get-in result [:execution/phase-results env-promotion-verify]))
              "Verify phase result should be present")
          (is (= env-id
                 (get-in result
                         [:execution/phase-results env-promotion-verify :result :environment-id]))
              ":execution/environment-id should appear in verify phase result")
          (is (.exists (io/file *test-worktree* "src/feature.clj"))
              "File written by implement agent should exist in executor environment"))))))

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
          workflow        (make-workflow env-promotion-implement env-promotion-done)]
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
          workflow (make-workflow env-promotion-implement env-promotion-done)]
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
          workflow        (make-workflow env-promotion-done)]
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
