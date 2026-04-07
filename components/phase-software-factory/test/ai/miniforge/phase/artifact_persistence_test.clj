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

(ns ai.miniforge.phase.artifact-persistence-test
  "Integration tests for environment promotion and fail-fast validation.

  Verifies that:
  - Phases fail fast when required artifacts are missing
  - Implement phase propagates agent errors correctly
  - Release phase validates artifacts before execution
  - Implement phase completes with environment-id when agent writes files directly"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   ;; Required for defmethod side effects (register phase handlers)
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.plan]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.implement]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.verify]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.release]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Test Fixtures

(def ^:dynamic *test-worktree* nil)

(defn create-temp-worktree []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "artifact-persist-test-" (random-uuid)))]
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

;------------------------------------------------------------------------------ Test Helpers

(defn create-base-context []
  {:execution/id (random-uuid)
   :execution/environment-id (random-uuid)
   :execution/worktree-path *test-worktree*
   :execution/input {:description "Test implementation"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {}})

(defn execute-phase-enter [phase-name ctx]
  (let [interceptor (registry/get-phase-interceptor {:phase phase-name})
        enter-fn (:enter interceptor)]
    (enter-fn ctx)))

(defn execute-phase-leave [phase-name ctx]
  (let [interceptor (registry/get-phase-interceptor {:phase phase-name})
        leave-fn (:leave interceptor)
        updated-ctx (leave-fn ctx)]
    (assoc-in updated-ctx [:execution/phase-results phase-name]
              (:phase updated-ctx))))

;------------------------------------------------------------------------------ Tests

(deftest test-verify-fails-without-environment
  (testing "verify phase throws when no execution environment-id is in context"
    (let [ctx (dissoc (create-base-context) :execution/environment-id)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Verify phase has no execution environment"
                            (execute-phase-enter :verify ctx)))
      ;; Verify ex-data contains diagnostic info
      (try
        (execute-phase-enter :verify ctx)
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :verify (:phase data)))
            (is (some? (:hint data)))))))))

(deftest test-release-skips-when-no-implement-result
  (testing "release phase skips when implement phase result is absent and worktree is clean"
    (let [ctx (-> (create-base-context)
                  ;; No implement result in phase-results (phase never ran)
                  (assoc :worktree-path *test-worktree*))
          result (execute-phase-enter :release ctx)]
      (is (= :completed (get-in result [:phase :status]))
          "Release should skip (complete) when implement status is nil and no dirty files"))))

(deftest test-release-fails-with-failed-implement
  (testing "release phase throws when implement result shows failure status"
    (let [ctx (-> (create-base-context)
                  ;; Simulate implement phase that failed
                  (assoc-in [:execution/phase-results :implement :result]
                            {:status :failed :error {:message "Agent timeout"}})
                  (assoc :worktree-path *test-worktree*))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Release phase has no code artifact"
                            (execute-phase-enter :release ctx))))))

(deftest test-implement-writes-to-environment
  (testing "implement phase completes with environment-id when agent writes files directly"
    (let [env-id (random-uuid)]
      (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                    agent/invoke (fn [_ _ ctx]
                                   ;; Simulate agent writing files directly to the environment
                                   (let [worktree-path (:execution/worktree-path ctx)
                                         file (io/file worktree-path "src/feature.clj")]
                                     (io/make-parents file)
                                     (spit file "(ns feature)\n(defn new-feature [] :implemented)"))
                                   (response/success nil {:tokens 1000 :duration-ms 2000}))]
        (let [ctx (-> (create-base-context)
                      (assoc :execution/environment-id env-id))
              ctx-entered (execute-phase-enter :implement ctx)
              ctx-left (execute-phase-leave :implement ctx-entered)]

          ;; Phase should complete
          (is (= :completed (get-in ctx-left [:phase :status]))
              "Implement phase should complete when agent writes files directly")

          ;; Result should have environment-id, not serialized code
          (is (= env-id (get-in ctx-left [:phase :result :environment-id]))
              "Phase result should reference the environment-id")
          (is (nil? (get-in ctx-left [:phase :result :output]))
              "Phase result should not carry serialized :output / :code/files")

          ;; Files written by agent should exist in the worktree
          (is (.exists (io/file *test-worktree* "src/feature.clj"))
              "Agent-written file should exist in the executor environment"))))))

(deftest test-implement-fails-on-agent-error
  (testing "leave-implement retries when agent returns :error and within budget"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 ;; Simulate agent returning error status
                                 (response/error "LLM timeout" {:tokens 0 :duration-ms 5000}))]
      (let [ctx (create-base-context)
            ctx-entered (execute-phase-enter :implement ctx)
            ctx-left (execute-phase-leave :implement ctx-entered)]
        ;; Agent returned :error status within retry budget — should retry
        (is (= :retrying (get-in ctx-left [:phase :status]))
            "Phase status should be :retrying when agent returns error within budget")
        (is (= :retrying (get-in ctx-left [:execution/phase-results :implement :status]))
            "Stored phase result should also show :retrying"))))

  (testing "leave-implement fails when agent returns :error and budget exhausted"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/error "LLM timeout" {:tokens 0 :duration-ms 5000}))]
      (let [ctx (-> (create-base-context)
                    (assoc-in [:phase :iterations] 8)) ;; At max budget (iterations=8)
            ctx-entered (execute-phase-enter :implement ctx)
            ctx-left (execute-phase-leave :implement ctx-entered)]
        (is (= :failed (get-in ctx-left [:phase :status]))
            "Phase status should be :failed when budget exhausted")
        (is (= :failed (get-in ctx-left [:execution/phase-results :implement :status]))
            "Stored phase result should also show :failed")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase.artifact-persistence-test)
  :leave-this-here)
