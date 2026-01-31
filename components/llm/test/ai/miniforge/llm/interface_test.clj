(ns ai.miniforge.llm.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.llm.interface :as llm]
            [ai.miniforge.llm.protocols.records.llm-client]
            [ai.miniforge.llm.protocols.impl.llm-client]))

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

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.llm.interface-test)

  :leave-this-here)
