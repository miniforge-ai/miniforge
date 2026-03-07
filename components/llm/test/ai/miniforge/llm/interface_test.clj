(ns ai.miniforge.llm.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.miniforge.llm.interface :as llm]
            [ai.miniforge.llm.protocols.records.llm-client]
            [ai.miniforge.llm.protocols.impl.llm-client :as impl]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def mock-response "This is a mock response")

;------------------------------------------------------------------------------ Layer 1
;; Client creation tests

(deftest create-client-test
  (testing "creates client with default backend"
    (let [client (llm/create-client)]
      (is (some? client))))

  (testing "creates client with specified backend"
    (let [client (llm/create-client {:backend :echo})]
      (is (some? client))))

  (testing "throws on unknown backend"
    (is (thrown? clojure.lang.ExceptionInfo
                 (llm/create-client {:backend :unknown})))))

(deftest mock-client-test
  (testing "creates mock client with output"
    (let [client (llm/mock-client {:output "Hello"})]
      (is (some? client))))

  (testing "creates mock client with multiple outputs"
    (let [client (llm/mock-client {:outputs ["A" "B" "C"]})]
      (is (some? client)))))

;; Completion tests with mock client

(deftest complete-test
  (testing "returns success for mock client"
    (let [client (llm/mock-client {:output "42"})
          resp (llm/complete client {:prompt "What is 6*7?"})]
      (is (llm/success? resp))
      (is (= "42" (llm/get-content resp)))))

  (testing "handles messages array"
    (let [client (llm/mock-client {:output "Hello!"})
          resp (llm/complete client {:messages [{:role "user" :content "Hi"}]})]
      (is (llm/success? resp))
      (is (= "Hello!" (llm/get-content resp)))))

  (testing "handles system prompt"
    (let [client (llm/mock-client {:output "I am helpful"})
          resp (llm/complete client {:system "Be helpful"
                                     :prompt "Who are you?"})]
      (is (llm/success? resp))))

  (testing "returns failure on non-zero exit"
    (let [client (llm/mock-client {:output "Error" :exit 1})
          resp (llm/complete client {:prompt "test"})]
      (is (not (llm/success? resp)))
      (is (some? (llm/get-error resp))))))

(deftest chat-test
  (testing "simple chat returns response"
    (let [client (llm/mock-client {:output "4"})
          resp (llm/chat client "2+2?")]
      (is (llm/success? resp))
      (is (= "4" (llm/get-content resp)))))

  (testing "chat with options"
    (let [client (llm/mock-client {:output "done"})
          resp (llm/chat client "explain" {:system "Be brief"})]
      (is (llm/success? resp)))))

(deftest multi-response-test
  (testing "mock client returns sequential responses"
    (let [client (llm/mock-client {:outputs ["First" "Second" "Third"]})
          r1 (llm/chat client "1")
          r2 (llm/chat client "2")
          r3 (llm/chat client "3")]
      (is (= "First" (llm/get-content r1)))
      (is (= "Second" (llm/get-content r2)))
      (is (= "Third" (llm/get-content r3)))))

  (testing "returns last response when exhausted"
    (let [client (llm/mock-client {:outputs ["A" "B"]})
          _ (llm/chat client "1")
          _ (llm/chat client "2")
          r3 (llm/chat client "3")]
      (is (= "B" (llm/get-content r3))))))

;; Response helper tests

(deftest response-helpers-test
  (testing "success? returns true for successful response"
    (is (llm/success? {:success true :content "ok"})))

  (testing "success? returns false for failed response"
    (is (not (llm/success? {:success false :error {:message "fail"}}))))

  (testing "get-content extracts content"
    (is (= "hello" (llm/get-content {:success true :content "hello"}))))

  (testing "get-error extracts error"
    (is (= {:type "err"} (llm/get-error {:success false :error {:type "err"}})))))

;; Echo backend test moved to integration tests
;; (actual CLI calls belong in projects/miniforge/test)

;; Backends registry test

(deftest backends-test
  (testing "backends contains expected keys"
    (is (contains? llm/backends :claude))
    (is (contains? llm/backends :echo))))

;; Streaming tests removed - they call actual CLIs via p/process and can't be
;; properly mocked in brick tests. Move to integration tests.

;------------------------------------------------------------------------------ Layer 2
;; Metrics tracking tests (PR #248)

(deftest parse-claude-stream-result-event-test
  (testing "result event extracts usage tokens"
    (let [line (json/generate-string
                 {:type "result"
                  :result {:usage {:input_tokens 1234
                                   :output_tokens 567}}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= "" (:delta parsed)))
      (is (true? (:done? parsed)))
      (is (= 1234 (get-in parsed [:usage :input-tokens])))
      (is (= 567 (get-in parsed [:usage :output-tokens])))))

  (testing "result event with no usage returns nil tokens"
    (let [line (json/generate-string {:type "result" :result {}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:done? parsed)))
      (is (nil? (get-in parsed [:usage :input-tokens]))))))

(deftest parse-codex-stream-line-test
  (testing "agent_message item extracts text delta"
    (let [line (json/generate-string
                 {:type "item.completed"
                  :item {:id "item_1" :type "agent_message"
                         :text "Hello world"}})
          parsed (impl/parse-codex-stream-line line)]
      (is (= "Hello world\n" (:delta parsed)))
      (is (false? (:done? parsed)))))

  (testing "turn.completed extracts usage and signals done"
    (let [line (json/generate-string
                 {:type "turn.completed"
                  :usage {:input_tokens 9971
                          :output_tokens 206}})
          parsed (impl/parse-codex-stream-line line)]
      (is (true? (:done? parsed)))
      (is (= 9971 (get-in parsed [:usage :input-tokens])))
      (is (= 206 (get-in parsed [:usage :output-tokens])))))

  (testing "mcp_tool_call item returns empty delta"
    (let [line (json/generate-string
                 {:type "item.completed"
                  :item {:id "item_2" :type "mcp_tool_call"
                         :server "artifact" :tool "submit_code_artifact"
                         :status "completed"}})
          parsed (impl/parse-codex-stream-line line)]
      (is (= "" (:delta parsed)))
      (is (false? (:done? parsed)))))

  (testing "reasoning item is ignored"
    (let [line (json/generate-string
                 {:type "item.completed"
                  :item {:id "item_0" :type "reasoning"
                         :text "Thinking..."}})
          parsed (impl/parse-codex-stream-line line)]
      (is (nil? parsed))))

  (testing "turn.failed returns error and done"
    (let [line (json/generate-string
                 {:type "turn.failed"
                  :error {:message "Rate limited"}})
          parsed (impl/parse-codex-stream-line line)]
      (is (true? (:done? parsed)))
      (is (str/includes? (:delta parsed) "Rate limited"))))

  (testing "thread.started is ignored"
    (let [line (json/generate-string {:type "thread.started" :thread_id "abc"})
          parsed (impl/parse-codex-stream-line line)]
      (is (nil? parsed)))))

(deftest parse-cli-output-includes-metrics-test
  (testing "success response includes :tokens and :usage keys"
    (let [resp (impl/parse-cli-output "hello world" 0)]
      (is (:success resp))
      (is (= "hello world" (:content resp)))
      (is (contains? resp :tokens))
      (is (contains? resp :usage))
      (is (= 0 (:tokens resp)))))

  (testing "error response includes :anomaly key"
    (let [resp (impl/parse-cli-output "bad" 1 "stderr")]
      (is (not (:success resp)))
      (is (some? (:error resp)))
      (is (some? (:anomaly resp))))))

(deftest mock-client-response-includes-metrics-test
  (testing "mock client success response has :tokens and :usage"
    (let [client (llm/mock-client {:output "ok"})
          resp (llm/complete client {:prompt "test"})]
      (is (contains? resp :tokens))
      (is (contains? resp :usage))
      (is (number? (:tokens resp)))))

  (testing "mock client failure response has :anomaly"
    (let [client (llm/mock-client {:output "err" :exit 1})
          resp (llm/complete client {:prompt "test"})]
      (is (some? (:anomaly resp))))))

(deftest stream-parser-accumulates-usage-test
  (testing "stream-with-parser stores usage from parsed events"
    (let [content (atom "")
          usage (atom nil)
          cost (atom nil)
          chunks (atom [])
          handler (impl/stream-with-parser
                    #'impl/parse-claude-stream-line
                    (fn [chunk] (swap! chunks conj chunk))
                    content
                    usage
                    cost)]
      ;; Feed an assistant event
      (handler (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "text" :text "Hello"}]}}))
      (is (= "Hello" @content))
      (is (nil? @usage))
      ;; Feed a result event with usage (top-level format from Claude CLI)
      (handler (json/generate-string
                 {:type "result"
                  :usage {:input_tokens 100
                          :output_tokens 50}
                  :total_cost_usd 0.0045}))
      (is (= {:input-tokens 100 :output-tokens 50} @usage))
      (is (= 0.0045 @cost)))))

;------------------------------------------------------------------------------ Layer 3
;; Rate limit detection tests

(deftest rate-limited?-test
  (testing "detects Claude CLI rate limit message"
    (is (impl/rate-limited? "You've hit your limit · resets 7pm (America/Los_Angeles)"))
    (is (impl/rate-limited? "You've hit your limit · resets 3am (UTC)")))

  (testing "detects generic rate limit phrasing"
    (is (impl/rate-limited? "rate limit exceeded")))

  (testing "does not flag normal content"
    (is (not (impl/rate-limited? "(ns example.core)\n(defn hello [] \"world\")")))
    (is (not (impl/rate-limited? "Here is the implementation...")))
    (is (not (impl/rate-limited? nil)))))

(deftest rate-limited-success-response-test
  (testing "rate limit content in success-response returns error"
    (let [resp (impl/success-response
                 "You've hit your limit · resets 7pm (America/Los_Angeles)" 0)]
      (is (not (:success resp)))
      (is (some? (:error resp)))
      (is (re-find #"rate limited" (:message (:error resp))))))

  (testing "normal content in success-response returns success"
    (let [resp (impl/success-response "(defn hello [] \"world\")" 0)]
      (is (:success resp)))))

(deftest rate-limited-streaming-success-response-test
  (testing "rate limit content in streaming-success-response returns error"
    (let [resp (impl/streaming-success-response
                 "You've hit your limit · resets 7pm (America/Los_Angeles)" 0 nil nil)]
      (is (not (:success resp)))
      (is (some? (:error resp)))))

  (testing "normal content in streaming-success-response returns success"
    (let [resp (impl/streaming-success-response "(defn foo [] 42)" 0 nil nil)]
      (is (:success resp)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.llm.interface-test)

  :leave-this-here)
