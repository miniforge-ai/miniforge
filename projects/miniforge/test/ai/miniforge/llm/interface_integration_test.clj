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

(ns ai.miniforge.llm.interface-integration-test
  "Integration tests for LLM interface that call actual CLI backends.

   These tests require actual CLI tools to be installed:
   - echo (standard UNIX command)
   - claude (optional - tests will skip if not available)

   Set MINIFORGE_SKIP_LLM_INTEGRATION=true to skip these tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.llm.interface :as llm]
            [ai.miniforge.llm.protocols.records.llm-client :as records]))

;------------------------------------------------------------------------------ Layer 0
;; Test configuration

(def skip-integration-tests?
  "Skip integration tests if env var is set."
  (= "true" (System/getenv "MINIFORGE_SKIP_LLM_INTEGRATION")))

(def run-claude-network-tests?
  "Tests that hit the real claude CLI (and therefore the network) only run
   when MINIFORGE_INTEGRATION_LLM_CLAUDE=true. They are skipped in CI to
   avoid flake from upstream latency or rate limits."
  (= "true" (System/getenv "MINIFORGE_INTEGRATION_LLM_CLAUDE")))

;------------------------------------------------------------------------------ Layer 1
;; Echo backend integration tests (uses actual echo CLI)

(deftest echo-backend-integration-test
  (when-not skip-integration-tests?
    (testing "echo backend returns prompt using real echo command"
      (let [client (llm/create-client {:backend :echo})
            resp (llm/chat client "Hello Echo")]
        (is (llm/success? resp))
        (is (= "Hello Echo" (llm/get-content resp)))))

    (testing "echo backend handles multi-word prompts"
      (let [client (llm/create-client {:backend :echo})
            resp (llm/chat client "This is a longer test message")]
        (is (llm/success? resp))
        (is (= "This is a longer test message" (llm/get-content resp)))))))

;; Streaming integration tests

(deftest complete-stream-integration-test
  (when-not skip-integration-tests?
    (testing "streaming with non-streaming backend (echo) falls back to complete"
      (let [chunks (atom [])
            client (llm/create-client {:backend :echo})
            resp (llm/complete-stream
                  client
                  {:prompt "Stream test"}
                  (fn [chunk]
                    (swap! chunks conj chunk)))]
        (is (llm/success? resp))
        (is (= "Stream test" (llm/get-content resp)))
        ;; Non-streaming backend should call callback once with full content
        (is (= 1 (count @chunks)))
        (is (:done? (first @chunks)))
        (is (= "Stream test" (:content (first @chunks))))))))

(deftest chat-stream-integration-test
  (when-not skip-integration-tests?
    (testing "chat-stream with non-streaming backend works"
      (let [chunks (atom [])
            client (llm/create-client {:backend :echo})
            resp (llm/chat-stream
                  client
                  "Hello streaming"
                  (fn [chunk]
                    (swap! chunks conj chunk)))]
        (is (llm/success? resp))
        (is (= "Hello streaming" (llm/get-content resp)))
        (is (pos? (count @chunks)))))))

;; Claude CLI streaming tests (only run if claude is available)

(deftest claude-streaming-integration-test
  (when (and (not skip-integration-tests?) run-claude-network-tests?)
    (testing "claude CLI supports actual streaming"
      (let [chunks (atom [])
            client (llm/create-client {:backend :claude})
            resp (llm/chat-stream
                  client
                  "Say 'test' once"
                  (fn [chunk]
                    (swap! chunks conj chunk))
                  {:system "You are a helpful assistant. Respond briefly."})]
        (is (llm/success? resp))
        (is (some? (llm/get-content resp)))
        ;; Should have received at least one chunk
        (is (pos? (count @chunks)))
        ;; Last chunk should be marked as done
        (is (:done? (last @chunks)))))))

;; Timeout verification tests

(deftest streaming-timeout-test
  (when-not skip-integration-tests?
    (testing "streaming with invalid command times out gracefully"
      ;; Use a backend with a fake exec-fn that returns immediately with a non-zero
      ;; exit, ensuring failure is reported rather than hung indefinitely.
      (let [chunks (atom [])
            client (records/create-client
                    {:backend :claude
                     :exec-fn (fn [_cmd]
                                {:out "" :err "Command not found" :exit 127})})
            resp (llm/complete-stream
                  client
                  {:prompt "test"}
                  (fn [chunk]
                    (swap! chunks conj chunk)))]
        ;; Should handle failure gracefully (not hang forever)
        (is (not (llm/success? resp)))
        (is (some? (llm/get-error resp)))))))

;; Backend configuration tests

(deftest backends-integration-test
  (when-not skip-integration-tests?
    (testing "backends registry is accessible"
      (is (contains? llm/backends :claude))
      (is (contains? llm/backends :echo)))

    (testing "echo backend is configured correctly"
      (let [backend-config (:echo llm/backends)]
        (is (some? backend-config))
        (is (= "echo" (:cmd backend-config)))
        (is (false? (:streaming? backend-config)))))

    (testing "claude backend is configured correctly"
      (let [backend-config (:claude llm/backends)]
        (is (some? backend-config))
        (is (= "claude" (:cmd backend-config)))
        (is (true? (:streaming? backend-config)))
        (is (fn? (:stream-parser backend-config)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.llm.interface-integration-test)
  :leave-this-here)
