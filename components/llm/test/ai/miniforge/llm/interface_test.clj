(ns ai.miniforge.llm.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
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
          parsed (#'impl/parse-claude-stream-line line)]
      (is (= "" (:delta parsed)))
      (is (true? (:done? parsed)))
      (is (= 1234 (get-in parsed [:usage :input-tokens])))
      (is (= 567 (get-in parsed [:usage :output-tokens])))))

  (testing "result event with no usage returns nil tokens"
    (let [line (json/generate-string {:type "result" :result {}})
          parsed (#'impl/parse-claude-stream-line line)]
      (is (true? (:done? parsed)))
      (is (nil? (get-in parsed [:usage :input-tokens]))))))

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
          chunks (atom [])
          handler (#'impl/stream-with-parser
                    #'impl/parse-claude-stream-line
                    (fn [chunk] (swap! chunks conj chunk))
                    content
                    usage)]
      ;; Feed an assistant event
      (handler (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "text" :text "Hello"}]}}))
      (is (= "Hello" @content))
      (is (nil? @usage))
      ;; Feed a result event with usage
      (handler (json/generate-string
                 {:type "result"
                  :result {:usage {:input_tokens 100
                                   :output_tokens 50}}}))
      (is (= {:input-tokens 100 :output-tokens 50} @usage)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.llm.interface-test)

  :leave-this-here)
