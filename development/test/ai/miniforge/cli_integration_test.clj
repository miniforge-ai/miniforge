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

(ns ai.miniforge.cli-integration-test
  "Integration tests for CLI backends.

   These tests verify that external CLI tools (claude, etc.)
   work correctly with miniforge's LLM interface.

   Tests marked ^:integration require real CLI access and may incur costs.
   Run with: clojure -M:poly test :dev :include :integration"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.test-harness :as harness]
   [ai.miniforge.llm.interface :as llm]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (harness/cleanup-test-state!)))))

;; ============================================================================
;; CLI Availability Tests
;; ============================================================================

(deftest cli-availability-test
  (testing "Claude CLI is available"
    (is (harness/cli-available? :claude)
        "Claude CLI should be installed")))

;; ============================================================================
;; Claude CLI Tests
;; ============================================================================

(deftest ^:integration claude-simple-prompt-test
  (testing "Claude CLI responds to simple prompt"
    (when (harness/cli-available? :claude)
      (let [client (llm/create-client {:backend :claude})
            response (llm/chat client "What is 2+2? Reply with only the number.")]
        (is (llm/success? response)
            "Request should succeed")
        (is (string? (llm/get-content response))
            "Response should have content")
        (is (re-find #"4" (llm/get-content response))
            "Response should contain the answer 4")))))

(deftest ^:integration claude-system-prompt-test
  (testing "Claude CLI respects system prompt"
    (when (harness/cli-available? :claude)
      (let [client (llm/create-client {:backend :claude})
            response (llm/chat client "Say hello"
                               {:system "You are a pirate. Always respond in pirate speak."})]
        (is (llm/success? response)
            "Request should succeed")
        ;; Pirate-speak typically includes these patterns
        (when (llm/success? response)
          (let [content (llm/get-content response)]
            (is (or (re-find #"(?i)ahoy" content)
                    (re-find #"(?i)arr" content)
                    (re-find #"(?i)matey" content)
                    (re-find #"(?i)ye" content)
                    (re-find #"(?i)avast" content)
                    (re-find #"(?i)me hearties" content))
                "Response should be in pirate speak")))))))

(deftest ^:integration claude-code-generation-test
  (testing "Claude CLI generates valid Clojure code"
    (when (harness/cli-available? :claude)
      (let [client (llm/create-client {:backend :claude})
            response (llm/complete client
                                    {:system "You are a Clojure expert. Return ONLY valid Clojure code, no explanations or markdown."
                                     :prompt "Write a function named `double-it` that doubles a number."})]
        (is (llm/success? response)
            "Request should succeed")
        ;; Try to read the code as Clojure
        (when (llm/success? response)
          (let [content (llm/get-content response)
                ;; Extract code from markdown if present
                code (if-let [match (re-find #"```(?:clojure)?\n([\s\S]*?)```" content)]
                       (second match)
                       content)]
            ;; Verify it's readable Clojure
            (is (try
                  (let [_parsed (read-string code)]
                    true)
                  (catch Exception _e
                    (println "Failed to parse:" code)
                    false))
                "Generated code should be valid Clojure")))))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest cli-error-handling-test
  (testing "Client handles errors gracefully"
    ;; Test with a backend that doesn't exist
    (is (thrown? Exception
                 (llm/create-client {:backend :nonexistent}))
        "Creating client with invalid backend should throw")))

;; ============================================================================
;; Mock Client Tests (Fast, no real CLI)
;; ============================================================================

(deftest mock-client-test
  (testing "Mock client returns configured output"
    (let [client (llm/mock-client {:output "test response"})
          response (llm/chat client "anything")]
      (is (llm/success? response))
      (is (= "test response" (llm/get-content response)))))

  (testing "Mock client handles sequential outputs"
    (let [client (llm/mock-client {:outputs ["first" "second" "third"]})
          r1 (llm/chat client "1")
          r2 (llm/chat client "2")
          r3 (llm/chat client "3")]
      (is (= "first" (llm/get-content r1)))
      (is (= "second" (llm/get-content r2)))
      (is (= "third" (llm/get-content r3))))))

;; ============================================================================
;; Client Factory Tests
;; ============================================================================

(deftest client-creation-test
  (testing "Can create client for each supported backend"
    (doseq [backend [:claude :echo]]
      (let [client (llm/create-client {:backend backend})]
        (is (some? client)
            (str "Should create client for " backend))))))

;; ============================================================================
;; Echo Backend Tests
;; ============================================================================

(deftest echo-backend-test
  (testing "Echo backend returns the prompt"
    (let [client (llm/create-client {:backend :echo})
          response (llm/chat client "Hello, world!")]
      (is (llm/success? response))
      (is (= "Hello, world!" (llm/get-content response))))))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'ai.miniforge.cli-integration-test)

  ;; Run just the fast tests (no real CLI)
  (clojure.test/test-vars [#'mock-client-test #'client-creation-test #'echo-backend-test])

  ;; Quick manual test with real Claude CLI
  (let [client (llm/create-client {:backend :claude})]
    (llm/chat client "Hi!"))

  :end)
