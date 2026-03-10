(ns ai.miniforge.llm.interface-integration-test
  "Integration tests for LLM interface that call actual CLI backends.

   These tests require actual CLI tools to be installed:
   - echo (standard UNIX command)
   - claude (optional - tests will skip if not available)

   Set MINIFORGE_SKIP_LLM_INTEGRATION=true to skip these tests."
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0
;; Test configuration

(def skip-integration-tests?
  "Skip integration tests if env var is set."
  (= "true" (System/getenv "MINIFORGE_SKIP_LLM_INTEGRATION")))

(defn claude-available?
  "Check if claude CLI is available."
  []
  (try
    ;; Just check if the client can be created, don't call it
    (some? (llm/create-client {:backend :claude}))
    (catch Exception _e false)))

;------------------------------------------------------------------------------ Layer 1
;; Echo backend integration tests (uses actual echo CLI)
;; DISABLED: These tests hang when run directly due to namespace loading issues
;; TODO: Fix test setup so they can run properly

#_(deftest echo-backend-integration-test
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

#_(deftest complete-stream-integration-test
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

#_(deftest chat-stream-integration-test
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

#_(deftest claude-streaming-integration-test
  (when (and (not skip-integration-tests?) (claude-available?))
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

#_(deftest streaming-timeout-test
  (when-not skip-integration-tests?
    (testing "streaming with invalid command times out gracefully"
      ;; Create a client with a non-existent command that will hang/fail
      (let [chunks (atom [])
            ;; Use a command that doesn't exist to trigger timeout behavior
            client (ai.miniforge.llm.protocols.records.llm-client/create-client
                    {:backend :claude
                     :exec-fn (fn [_cmd]
                                ;; Simulate a command that never completes
                                ;; by returning immediately with empty output
                                ;; The actual timeout is in stream-exec-fn
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

#_(deftest backends-integration-test
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
  (test/run-tests 'ai.miniforge.llm.interface-integration-test)

  ;; Tests are currently disabled with #_ - fix test setup first
  ;; (echo-backend-integration-test)
  ;; (complete-stream-integration-test)
  ;; (claude-streaming-integration-test)

  :leave-this-here)
