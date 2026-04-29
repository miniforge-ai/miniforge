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

(ns ai.miniforge.release-executor.pr-body-test
  "Tests for PR body update: sandbox/edit-pr-body! command generation
   and core/step-update-pr-body pipeline step behavior."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.release-executor.sandbox :as sandbox]))

;; ============================================================================
;; Mock executor
;; ============================================================================

(defn- create-mock-executor
  "Create a mock executor that records commands and returns configurable results."
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
;; sandbox/edit-pr-body! — command generation
;; ============================================================================

(deftest edit-pr-body-generates-gh-pr-edit-command-test
  (testing "edit-pr-body! calls gh pr edit with pr number and body"
    (let [[exec cmds] (create-mock-executor)
          _result (sandbox/edit-pr-body! exec "env-1" 42 "## Summary\nNew body")]
      (is (= 1 (count @cmds)))
      (let [cmd (first @cmds)]
        (is (clojure.string/includes? cmd "gh pr edit 42"))
        (is (clojure.string/includes? cmd "--body"))
        (is (clojure.string/includes? cmd "## Summary"))))))

(deftest edit-pr-body-escapes-single-quotes-test
  (testing "edit-pr-body! escapes single quotes in body"
    (let [[exec cmds] (create-mock-executor)
          _result (sandbox/edit-pr-body! exec "env-1" 10 "it's a test")
          cmd (first @cmds)]
      (is (clojure.string/includes? cmd "it'\\''s a test")))))

(deftest edit-pr-body-handles-nil-body-test
  (testing "edit-pr-body! treats nil body as empty string"
    (let [[exec cmds] (create-mock-executor)
          _result (sandbox/edit-pr-body! exec "env-1" 5 nil)
          cmd (first @cmds)]
      (is (clojure.string/includes? cmd "gh pr edit 5"))
      (is (clojure.string/includes? cmd "--body")))))

(deftest edit-pr-body-forwards-exec-opts-test
  (testing "edit-pr-body! accepts optional exec-opts (e.g. :env for GH_TOKEN)"
    (let [[exec cmds] (create-mock-executor)]
      ;; Should not throw — opts are forwarded to exec!
      (sandbox/edit-pr-body! exec "env-1" 7 "body" {:env {"GH_TOKEN" "tok"}})
      (is (= 1 (count @cmds))))))

(deftest edit-pr-body-returns-success-on-zero-exit-test
  (testing "edit-pr-body! returns success when executor exits 0"
    (let [[exec _cmds] (create-mock-executor)
          result (sandbox/edit-pr-body! exec "env-1" 1 "body")]
      (is (:success? result)))))

(deftest edit-pr-body-returns-failure-on-nonzero-exit-test
  (testing "edit-pr-body! returns failure when executor exits non-zero"
    (let [[exec _cmds] (create-mock-executor
                         :responses {"gh pr edit" {:exit-code 1
                                                   :stdout ""
                                                   :stderr "HTTP 403"}})
          result (sandbox/edit-pr-body! exec "env-1" 1 "body")]
      (is (not (:success? result)))
      (is (some? (:error result))))))

;; ============================================================================
;; core/step-update-pr-body — pipeline step
;; ============================================================================

(defn- make-state
  "Build a minimal pipeline state for step-update-pr-body."
  [overrides]
  (let [[exec _cmds] (create-mock-executor)]
    (merge {:create-pr? true
            :pr-number 42
            :pr-url "https://github.com/org/repo/pull/42"
            :branch "feat/test"
            :executor exec
            :environment-id "env-1"
            :release-meta {:release/pr-title "feat: test"
                           :release/pr-description "## Summary\nChange"
                           :release/pr-body "## Summary\nChange"
                           :release/commit-message "feat: test"}}
           overrides)))

(deftest step-update-pr-body-skips-on-failure-test
  (testing "step-update-pr-body passes through failed state unchanged"
    (let [state (assoc (make-state {}) :failure {:type :prior-failure})]
      (is (= state (core/step-update-pr-body state))))))

(deftest step-update-pr-body-skips-when-no-create-pr-test
  (testing "step-update-pr-body returns state when :create-pr? is false"
    (let [state (make-state {:create-pr? false})]
      (is (= state (core/step-update-pr-body state))))))

(deftest step-update-pr-body-skips-when-no-pr-number-test
  (testing "step-update-pr-body returns state when :pr-number is nil"
    (let [state (make-state {:pr-number nil})]
      (is (= state (core/step-update-pr-body state))))))

(deftest step-update-pr-body-calls-edit-pr-body-test
  (testing "step-update-pr-body calls sandbox/edit-pr-body! and returns state"
    (let [called (atom false)]
      (with-redefs [sandbox/edit-pr-body!
                    (fn [_exec _env-id pr-number body _opts]
                      (reset! called true)
                      (is (= 42 pr-number))
                      (is (= "## Summary\nChange" body))
                      {:success? true})]
        (let [state (make-state {})
              result (core/step-update-pr-body state)]
          (is @called)
          ;; step-update-pr-body returns state (not the edit result)
          (is (= (:pr-number state) (:pr-number result)))
          (is (not (contains? result :failure))))))))

(deftest step-update-pr-body-prefers-generated-doc-content-test
  (testing "generated PR doc content overrides the initial fallback body"
    (let [seen-body (atom nil)]
      (with-redefs [sandbox/edit-pr-body!
                    (fn [_exec _env-id _pr-number body _opts]
                      (reset! seen-body body)
                      {:success? true})]
        (core/step-update-pr-body
         (make-state {:pr-doc-content "# Rich Doc\n\n## Summary\nGenerated"}))
        (is (= "# Rich Doc\n\n## Summary\nGenerated" @seen-body))))))

(deftest step-update-pr-body-survives-exception-test
  (testing "step-update-pr-body catches exceptions and returns state (non-fatal)"
    (with-redefs [sandbox/edit-pr-body!
                  (fn [& _args]
                    (throw (ex-info "Network error" {})))]
      (let [state (make-state {})
            result (core/step-update-pr-body state)]
        ;; Non-fatal — no :failure key
        (is (not (contains? result :failure)))))))

(deftest step-update-pr-body-passes-github-token-test
  (testing "step-update-pr-body threads github-token via gh-exec-opts"
    (let [seen-opts (atom nil)]
      (with-redefs [sandbox/edit-pr-body!
                    (fn [_exec _env-id _pr-number _body opts]
                      (reset! seen-opts opts)
                      {:success? true})]
        (let [state (make-state {:github-token "my-token"})]
          (core/step-update-pr-body state)
          (is (= {"GH_TOKEN" "my-token"} (:env @seen-opts))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.release-executor.pr-body-test)
  :leave-this-here)
