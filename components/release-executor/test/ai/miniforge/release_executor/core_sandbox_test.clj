(ns ai.miniforge.release-executor.core-sandbox-test
  "Tests for dual-mode dispatch in release-executor core.

   Verifies that execute-release-phase correctly dispatches to sandbox
   operations when :executor and :environment-id are present in context,
   and falls back to git operations when they are absent."
  (:require
   [ai.miniforge.dag-executor.protocols.executor]
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.dag-executor.result :as dag-result]))

;; ============================================================================
;; Mock executor (same pattern as sandbox_test.clj)
;; ============================================================================

(defn create-mock-executor
  "Create a mock executor that succeeds for all operations."
  [& {:keys [responses default-response]
      :or {responses {}
           default-response {:exit-code 0 :stdout "" :stderr ""}}}]
  (let [commands (atom [])]
    [(reify
       ai.miniforge.dag-executor.protocols.executor/TaskExecutor
       (executor-type [_] :mock)
       (available? [_] (dag-result/ok {:available? true}))
       (acquire-environment! [_ _ _] (dag-result/ok {:environment-id "mock-env"}))
       (execute! [_ _env-id command _opts]
         (swap! commands conj command)
         (let [response (or (some (fn [[substr resp]]
                                    (when (clojure.string/includes? (str command) substr)
                                      resp))
                                  responses)
                            default-response)]
           (dag-result/ok response)))
       (copy-to! [_ _ _ _] (dag-result/ok {}))
       (copy-from! [_ _ _ _] (dag-result/ok {}))
       (release-environment! [_ _] (dag-result/ok {:released? true}))
       (environment-status [_ _] (dag-result/ok {:status :running})))
     commands]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defn make-workflow-state
  "Create a minimal workflow state with code artifacts."
  [files]
  {:workflow/artifacts [{:artifact/type :code
                         :artifact/content {:code/id (random-uuid)
                                           :code/files files}}]
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
;; Sandbox mode detection tests
;; ============================================================================

(deftest sandbox-mode-detected-when-executor-present
  (testing "sandbox? is true when both :executor and :environment-id in context"
    (let [[exec cmds] (create-mock-executor
                       :responses {"gh auth" {:exit-code 0 :stdout "Logged in" :stderr ""}
                                   "git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                   "git fetch" {:exit-code 0 :stdout "" :stderr ""}
                                   "git checkout" {:exit-code 0 :stdout "" :stderr ""}
                                   "git commit" {:exit-code 0 :stdout "" :stderr ""}
                                   "git rev-parse" {:exit-code 0 :stdout "abc123\n" :stderr ""}
                                   "git push" {:exit-code 0 :stdout "" :stderr ""}
                                   "gh pr create" {:exit-code 0 :stdout "https://github.com/o/r/pull/1\n" :stderr ""}})
          workflow-state (make-workflow-state
                          [{:action :create :path "src/a.clj" :content "(ns a)"}])
          context {:executor exec
                   :environment-id "mock-env"
                   :create-pr? false}  ;; skip PR creation for simplicity
          result (core/execute-release-phase workflow-state context
                                             {:release-meta test-release-meta})]
      ;; Should succeed via sandbox path
      (is (:success? result))
      ;; Commands should have been sent to the mock executor
      (is (pos? (count @cmds)))
      ;; Should contain sandbox operations (base64 write, git add)
      (is (some #(clojure.string/includes? % "base64 -d") @cmds))
      (is (some #(clojure.string/includes? % "git add") @cmds)))))

(deftest host-mode-when-no-executor
  (testing "sandbox? is false when :executor is nil"
    (let [workflow-state (make-workflow-state
                          [{:action :create :path "src/a.clj" :content "(ns a)"}])
          ;; No :executor or :environment-id -> host mode
          ;; Missing worktree-path should cause validation failure
          context {:create-pr? false}
          result (core/execute-release-phase workflow-state context {})]
      ;; Should fail because no worktree-path and not sandbox
      (is (not (:success? result)))
      (is (= :missing-worktree-path (:type (first (:errors result))))))))

(deftest sandbox-mode-skips-worktree-validation
  (testing "sandbox mode does not require :worktree-path"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh auth" {:exit-code 0 :stdout "" :stderr ""}
                                    "git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                    "git fetch" {:exit-code 0 :stdout "" :stderr ""}
                                    "git checkout" {:exit-code 0 :stdout "" :stderr ""}
                                    "git commit" {:exit-code 0 :stdout "" :stderr ""}
                                    "git rev-parse" {:exit-code 0 :stdout "abc123\n" :stderr ""}})
          workflow-state (make-workflow-state
                          [{:action :create :path "src/a.clj" :content "(ns a)"}])
          ;; No :worktree-path but has :executor -> should NOT fail validation
          context {:executor exec
                   :environment-id "mock-env"
                   :create-pr? false}
          result (core/execute-release-phase workflow-state context
                                             {:release-meta test-release-meta})]
      (is (:success? result)))))

(deftest sandbox-no-code-artifacts-still-fails
  (testing "sandbox mode still fails when no code artifacts"
    (let [[exec _cmds] (create-mock-executor)
          workflow-state {:workflow/artifacts []
                          :workflow/spec {:spec/description "test"}}
          context {:executor exec
                   :environment-id "mock-env"
                   :create-pr? false}
          result (core/execute-release-phase workflow-state context {})]
      (is (not (:success? result)))
      (is (= :no-code-artifacts (:type (first (:errors result))))))))
