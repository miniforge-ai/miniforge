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

(ns ai.miniforge.agent-runtime.error-classifier-test
  "Unit tests for error classification and message generation."
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.string :as str]
            [ai.miniforge.agent-runtime.interface :as classifier]))

;------------------------------------------------------------------------------ Layer 0 Tests
;; Pattern matching and classification

(deftest test-agent-backend-classification
  (testing "Agent backend error patterns"
    (are [error-msg] (= :agent-backend
                       (:type (classifier/classify-error error-msg nil)))
      "classifyHandoffIfNeeded is not defined"
      "ReferenceError: agentCleanup is not defined"
      "TypeError: agent.handoff is not a function"
      "Cannot read property 'handoff' of undefined"
      "null toolResult received"
      "agent is undefined"
      "handoff failed for agent")))

(deftest test-task-code-classification
  (testing "Task code error patterns"
    (are [error-msg] (= :task-code
                       (:type (classifier/classify-error error-msg nil)))
      "Syntax error in components/foo.clj:42"
      "Compilation failed with 5 errors"
      "linting took 100ms, errors: 3"
      "test suite failed: 2 assertions"
      "Could not resolve namespace ai.miniforge.foo"
      "No such file or directory: /tmp/test.clj"
      "Unexpected token at line 42"
      "Parse error: missing closing parenthesis")))

(deftest test-external-classification
  (testing "External service error patterns"
    (are [error-msg] (= :external
                       (:type (classifier/classify-error error-msg nil)))
      "ECONNREFUSED: Connection refused to github.com"
      "Network error: timeout after 30000ms"
      "GitHub API rate limit exceeded"
      "API service unavailable"
      "Request timeout after 5000ms"
      "502 Bad Gateway from api.anthropic.com"
      "503 Service Unavailable"
      "Connection refused to api.github.com"
      "DNS lookup failed for github.com")))

(deftest test-default-classification
  (testing "Unknown errors default to task-code (conservative)"
    (is (= :task-code
           (:type (classifier/classify-error "Some unknown error" nil))))))

(deftest test-vendor-attribution
  (testing "Correct vendor attribution for each error type"
    (is (= "Claude Code"
           (:vendor (classifier/classify-error "classifyHandoffIfNeeded is not defined" nil))))
    (is (= "miniforge"
           (:vendor (classifier/classify-error "Syntax error in foo.clj" nil))))
    (is (= "External Service"
           (:vendor (classifier/classify-error "ECONNREFUSED" nil))))))

(deftest test-extract-completed-work
  (testing "Extract work items from task state"
    (is (= []
           (classifier/extract-completed-work nil)))

    (is (= ["Created 4 files (523 lines)"]
           (classifier/extract-completed-work {:files-created 4
                                               :lines-written 523})))

    (is (= ["Created 4 files (523 lines)"
            "All tests passed (789 assertions)"
            "PR created: https://github.com/org/repo/pull/151"]
           (classifier/extract-completed-work {:files-created 4
                                               :lines-written 523
                                               :tests-run 10
                                               :tests-passed true
                                               :test-assertions 789
                                               :pr-url "https://github.com/org/repo/pull/151"})))

    (is (= ["3 tests failed"
            "Created branch: feat/new-feature"]
           (classifier/extract-completed-work {:tests-failed 3
                                               :branch-created "feat/new-feature"})))

    (is (= ["Created 2 files"
            "PR created: https://github.com/org/repo/pull/42"
            "PR merged successfully"]
           (classifier/extract-completed-work {:files-created 2
                                               :pr-url "https://github.com/org/repo/pull/42"
                                               :pr-merged true})))

    (is (= ["5 commits made"]
           (classifier/extract-completed-work {:commits-made 5})))))

;------------------------------------------------------------------------------ Layer 1 Tests
;; Message formatting

(deftest test-format-error-message
  (testing "Agent backend error message formatting"
    (let [classified (classifier/classify-error
                      "classifyHandoffIfNeeded is not defined"
                      {:files-created 4
                       :lines-written 523
                       :pr-url "https://github.com/org/repo/pull/151"
                       :pr-merged true})
          message (classifier/format-error-message classified)]
      (is (str/includes? message "⚠️  Agent System Error"))
      (is (str/includes? message "Not Your Fault"))
      (is (str/includes? message "Your task completed successfully"))
      (is (str/includes? message "✅ Created 4 files (523 lines)"))
      (is (str/includes? message "classifyHandoffIfNeeded is not defined"))
      (is (str/includes? message "Claude Code's agent runtime"))
      (is (str/includes? message "No need to retry"))))

  (testing "Agent backend error without completed work"
    (let [classified (classifier/classify-error
                      "ReferenceError: agentCleanup is not defined"
                      nil)
          message (classifier/format-error-message classified)]
      (is (str/includes? message "⚠️  Agent System Error"))
      (is (not (str/includes? message "Your task completed successfully")))
      (is (str/includes? message "Report this bug and try again later"))))

  (testing "Task code error message formatting"
    (let [classified (classifier/classify-error
                      "Syntax error in components/foo.clj:42"
                      {:files-created 2})
          message (classifier/format-error-message classified)]
      (is (str/includes? message "❌ Task Code Error"))
      (is (str/includes? message "Syntax error in components/foo.clj:42"))
      (is (str/includes? message "Fix the issue and retry"))
      (is (str/includes? message "Partial work completed"))
      (is (str/includes? message "⏸️  Created 2 files"))))

  (testing "External error message formatting"
    (let [classified (classifier/classify-error
                      "GitHub API rate limit exceeded"
                      {:branch-created "feat/new-feature"})
          message (classifier/format-error-message classified)]
      (is (str/includes? message "⚠️  External Service Error"))
      (is (str/includes? message "GitHub API rate limit exceeded"))
      (is (str/includes? message "Wait a few minutes and retry"))
      (is (str/includes? message "Partial work completed"))
      (is (str/includes? message "✅ Created branch: feat/new-feature")))))

;------------------------------------------------------------------------------ Layer 2 Tests
;; URL generation and retry logic

(deftest test-generate-report-url
  (testing "Claude Code report URL generation"
    (let [url (classifier/generate-report-url
               :agent-backend
               "Claude Code"
               {:message "classifyHandoffIfNeeded is not defined"
                :task-id "abc123"
                :timestamp "2026-02-08"})]
      (is (str/starts-with? url "https://github.com/anthropics/claude-code/issues/new"))
      (is (str/includes? url "title=Agent+Backend+Error"))
      (is (str/includes? url "classifyHandoffIfNeeded"))))

  (testing "Miniforge report URL generation"
    (let [url (classifier/generate-report-url
               :task-code
               "miniforge"
               {:message "Syntax error"
                :task-id "xyz789"})]
      (is (str/starts-with? url "https://github.com/miniforge-ai/miniforge/issues/new"))
      (is (str/includes? url "title=Task+Error"))))

  (testing "External errors have no report URL"
    (let [classified (classifier/classify-error "ECONNREFUSED" nil)]
      (is (nil? (:report-url classified))))))

(deftest test-should-retry
  (testing "Agent backend errors with completed work - don't retry"
    (let [classified (classifier/classify-error
                      "classifyHandoffIfNeeded is not defined"
                      {:files-created 4 :pr-merged true})]
      (is (false? (:should-retry classified)))))

  (testing "Agent backend errors without completed work - maybe retry"
    (let [classified (classifier/classify-error
                      "ReferenceError: agentCleanup is not defined"
                      nil)]
      (is (true? (:should-retry classified)))))

  (testing "Task code errors - always retry after fixing"
    (let [classified (classifier/classify-error
                      "Syntax error"
                      {:files-created 2})]
      (is (true? (:should-retry classified)))))

  (testing "External errors - always retry (transient)"
    (let [classified (classifier/classify-error
                      "Network error: timeout"
                      nil)]
      (is (true? (:should-retry classified))))))

;------------------------------------------------------------------------------ Integration Tests
;; Full classification flow

(deftest test-classify-error-full
  (testing "Full classification of agent backend error"
    (let [result (classifier/classify-error
                  (ex-info "classifyHandoffIfNeeded is not defined" {})
                  {:files-created 4
                   :lines-written 523
                   :pr-url "https://github.com/org/repo/pull/151"
                   :pr-merged true
                   :task-id "task-123"})]
      (is (= :agent-backend (:type result)))
      (is (= "Claude Code" (:vendor result)))
      (is (= "classifyHandoffIfNeeded is not defined" (:message result)))
      (is (= 3 (count (:completed-work result))))
      (is (string? (:report-url result)))
      (is (str/includes? (:report-url result) "github.com"))
      (is (false? (:should-retry result)))))

  (testing "Full classification of task code error"
    (let [result (classifier/classify-error
                  "Syntax error in foo.clj:42"
                  {:task-id "task-456"})]
      (is (= :task-code (:type result)))
      (is (= "miniforge" (:vendor result)))
      (is (true? (:should-retry result)))
      (is (string? (:report-url result)))))

  (testing "Full classification of external error"
    (let [result (classifier/classify-error
                  "ECONNREFUSED: Connection refused"
                  {:branch-created "feat/test"})]
      (is (= :external (:type result)))
      (is (= "External Service" (:vendor result)))
      (is (= 1 (count (:completed-work result))))
      (is (nil? (:report-url result)))
      (is (true? (:should-retry result))))))

(deftest test-exception-handling
  (testing "Classify Exception objects"
    (let [ex (ex-info "Test error" {:some :data})
          result (classifier/classify-error ex nil)]
      (is (= :task-code (:type result)))
      (is (= "Test error" (:message result)))
      (is (= ex (:original-error result)))))

  (testing "Classify string errors"
    (let [result (classifier/classify-error "Simple error string" nil)]
      (is (= :task-code (:type result)))
      (is (= "Simple error string" (:message result)))
      (is (= "Simple error string" (:original-error result))))))
