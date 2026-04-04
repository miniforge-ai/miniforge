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

(ns ai.miniforge.release-executor.core-sandbox-test
  "Tests for environment-model dispatch in release-executor core.

   Verifies that execute-release-phase always routes through the executor
   (sandbox) path — stages dirty files instead of writing from code artifacts."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.core :as core]))

;; ============================================================================
;; Mock executor
;; ============================================================================

(defn create-mock-executor
  "Create a mock executor that succeeds for all operations."
  [& {:keys [responses default-response]
      :or {responses {}
           default-response {:exit-code 0 :stdout "" :stderr ""}}}]
  (let [commands (atom [])]
    [(reify
       dag/TaskExecutor
       (executor-type [_] :mock)
       (available? [_] (dag/ok {:available? true}))
       (acquire-environment! [_ _ _] (dag/ok {:environment-id "mock-env"}))
       (execute! [_ _env-id command _opts]
         (swap! commands conj command)
         (let [response (or (some (fn [[substr resp]]
                                    (when (clojure.string/includes? (str command) substr)
                                      resp))
                                  responses)
                            default-response)]
           (dag/ok response)))
       (copy-to! [_ _ _ _] (dag/ok {}))
       (copy-from! [_ _ _ _] (dag/ok {}))
       (release-environment! [_ _] (dag/ok {:released? true}))
       (environment-status [_ _] (dag/ok {:status :running})))
     commands]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn make-workflow-state
  "Create a minimal workflow state."
  []
  {:workflow/artifacts []
   :workflow/spec {:spec/description "test task"}})

(def test-release-meta
  "Pre-built release metadata so tests don't need an LLM backend."
  {:release/id (random-uuid)
   :release/branch-name "feature/test-change"
   :release/commit-message "feat: test change"
   :release/pr-title "feat: test change"
   :release/pr-description "## Summary\nTest change"
   :release/created-at (java.util.Date.)})

;; ============================================================================
;; Environment model: executor always required
;; ============================================================================

(deftest executor-required-for-release
  (testing "execute-release-phase fails without :executor in context"
    (let [result (core/execute-release-phase
                  (make-workflow-state)
                  {:worktree-path "/tmp/wt" :environment-id "env-1" :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :missing-executor (:type (first (:errors result))))))))

(deftest environment-id-required-for-release
  (testing "execute-release-phase fails without :environment-id in context"
    (let [[exec _] (create-mock-executor)
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:worktree-path "/tmp/wt" :executor exec :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :missing-environment-id (:type (first (:errors result))))))))

(deftest worktree-path-required-for-release
  (testing "execute-release-phase fails without :worktree-path in context"
    (let [[exec _] (create-mock-executor)
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec :environment-id "env-1" :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :missing-worktree-path (:type (first (:errors result))))))))

;; ============================================================================
;; Executor dispatch: git add instead of base64 write
;; ============================================================================

(deftest release-stages-dirty-files-not-base64
  (testing "release executor stages dirty files — no base64 file writes"
    (let [[exec cmds] (create-mock-executor
                       :responses {"gh auth"        {:exit-code 0 :stdout "Logged in" :stderr ""}
                                   "git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                   "git fetch"       {:exit-code 0 :stdout "" :stderr ""}
                                   "git checkout"    {:exit-code 0 :stdout "" :stderr ""}
                                   "git add"         {:exit-code 0 :stdout "" :stderr ""}
                                   "git diff"        {:exit-code 0 :stdout "src/a.clj\n" :stderr ""}
                                   "git commit"      {:exit-code 0 :stdout "" :stderr ""}
                                   "git rev-parse"   {:exit-code 0 :stdout "abc123\n" :stderr ""}})
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec
                   :environment-id "mock-env"
                   :worktree-path "/tmp/wt"
                   :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (:success? result))
      ;; git add . was sent to executor
      (is (some #(clojure.string/includes? % "git add") @cmds))
      ;; No base64 writes — files already in worktree
      (is (not (some #(clojure.string/includes? % "base64 -d") @cmds))))))

(deftest release-fails-when-nothing-staged
  (testing "release fails when git diff --cached shows nothing staged"
    (let [[exec _] (create-mock-executor
                    :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                "git fetch"        {:exit-code 0 :stdout "" :stderr ""}
                                "git checkout"     {:exit-code 0 :stdout "" :stderr ""}
                                "git add"          {:exit-code 0 :stdout "" :stderr ""}
                                "git diff"         {:exit-code 0 :stdout "" :stderr ""}}) ;; empty — nothing staged
          result (core/execute-release-phase
                  (make-workflow-state)
                  {:executor exec
                   :environment-id "mock-env"
                   :worktree-path "/tmp/wt"
                   :create-pr? false}
                  {:release-meta test-release-meta})]
      (is (not (:success? result)))
      (is (= :no-files-to-stage (:type (first (:errors result))))))))
