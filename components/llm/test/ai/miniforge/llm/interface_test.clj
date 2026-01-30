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

;; Echo backend test (uses actual CLI)

(deftest echo-backend-test
  (testing "echo backend returns prompt"
    (let [client (llm/create-client {:backend :echo})
          resp (llm/chat client "Hello Echo")]
      (is (llm/success? resp))
      (is (= "Hello Echo" (llm/get-content resp))))))

;; Backends registry test

(deftest backends-test
  (testing "backends contains expected keys"
    (is (contains? llm/backends :claude))
    (is (contains? llm/backends :echo))))

;; Streaming tests

(deftest complete-stream-test
  (testing "streams chunks with mock streaming client"
    (let [chunks (atom [])
          client (llm/mock-client {:output "Hello World"})
          resp (llm/complete-stream
                client
                {:prompt "test"}
                (fn [chunk]
                  (swap! chunks conj chunk)))]
      (is (llm/success? resp))
      (is (= "Hello World" (llm/get-content resp)))
      ;; Non-streaming backend should call callback once with full content
      (is (= 1 (count @chunks)))
      (is (:done? (first @chunks)))
      (is (= "Hello World" (:content (first @chunks))))))

  (testing "handles streaming chunks in sequence"
    (let [chunks (atom [])
          ;; Create a mock exec-fn that simulates streaming output
          mock-stream-exec (fn [_cmd]
                            {:out (str "{\"type\":\"stream_event\",\"event\":{\"delta\":{\"text\":\"Hello\"}}}\n"
                                       "{\"type\":\"stream_event\",\"event\":{\"delta\":{\"text\":\" \"}}}\n"
                                       "{\"type\":\"stream_event\",\"event\":{\"delta\":{\"text\":\"World\"}}}")
                             :err ""
                             :exit 0})
          client (#'ai.miniforge.llm.protocols.records.llm-client/create-client
                  {:backend :claude :exec-fn mock-stream-exec})
          resp (llm/complete-stream
                client
                {:prompt "test"}
                (fn [chunk]
                  (swap! chunks conj chunk)))]
      ;; With actual streaming, we get chunks + final
      (is (llm/success? resp))
      (is (= "Hello World" (llm/get-content resp)))
      (is (> (count @chunks) 1))
      ;; Last chunk should be done
      (is (:done? (last @chunks)))
      ;; Content should accumulate
      (is (= "Hello World" (:content (last @chunks))))))

  (testing "handles stream errors gracefully"
    (let [chunks (atom [])
          client (llm/mock-client {:output "error" :exit 1})
          resp (llm/complete-stream
                client
                {:prompt "test"}
                (fn [chunk]
                  (swap! chunks conj chunk)))]
      (is (not (llm/success? resp)))
      (is (some? (llm/get-error resp))))))

(deftest chat-stream-test
  (testing "chat-stream works with simple prompt"
    (let [chunks (atom [])
          client (llm/mock-client {:output "Response"})
          resp (llm/chat-stream
                client
                "Hello"
                (fn [chunk]
                  (swap! chunks conj chunk)))]
      (is (llm/success? resp))
      (is (= "Response" (llm/get-content resp)))
      (is (pos? (count @chunks)))))

  (testing "chat-stream with options"
    (let [chunks (atom [])
          client (llm/mock-client {:output "Brief response"})
          resp (llm/chat-stream
                client
                "Explain"
                (fn [chunk]
                  (swap! chunks conj chunk))
                {:system "Be brief"})]
      (is (llm/success? resp))
      (is (= "Brief response" (llm/get-content resp))))))

(deftest stream-accumulation-test
  (testing "content accumulates correctly during streaming"
    (let [chunks (atom [])
          ;; Simulate multiple stream chunks
          mock-exec (fn [_cmd]
                      {:out (str "{\"type\":\"stream_event\",\"event\":{\"delta\":{\"text\":\"A\"}}}\n"
                                 "{\"type\":\"stream_event\",\"event\":{\"delta\":{\"text\":\"B\"}}}\n"
                                 "{\"type\":\"stream_event\",\"event\":{\"delta\":{\"text\":\"C\"}}}")
                       :err ""
                       :exit 0})
          client (#'ai.miniforge.llm.protocols.records.llm-client/create-client
                  {:backend :claude :exec-fn mock-exec})
          _resp (llm/complete-stream
                 client
                 {:prompt "test"}
                 (fn [chunk]
                   (swap! chunks conj chunk)))
          non-final-chunks (drop-last @chunks)]
      ;; Verify content accumulates
      (when (seq non-final-chunks)
        (is (= "A" (:content (first non-final-chunks))))
        (when (> (count non-final-chunks) 1)
          (is (= "AB" (:content (second non-final-chunks))))))
      ;; Final chunk has all content
      (is (= "ABC" (:content (last @chunks)))))))

  (deftest stream-parser-test
    (testing "claude stream parser handles valid JSON"
      (let [parser (#'ai.miniforge.llm.protocols.impl.llm-client/backends :claude)
            stream-parser (:stream-parser parser)
            valid-line "{\"type\":\"stream_event\",\"event\":{\"delta\":{\"text\":\"Hello\"}}}"
            result (stream-parser valid-line)]
        (is (some? result))
        (is (= "Hello" (:delta result)))
        (is (false? (:done? result)))))

    (testing "claude stream parser ignores non-stream events"
      (let [parser (#'ai.miniforge.llm.protocols.impl.llm-client/backends :claude)
            stream-parser (:stream-parser parser)
            non-stream "{\"type\":\"other_event\",\"data\":\"something\"}"
            result (stream-parser non-stream)]
        (is (nil? result))))

    (testing "claude stream parser handles malformed JSON gracefully"
      (let [parser (#'ai.miniforge.llm.protocols.impl.llm-client/backends :claude)
            stream-parser (:stream-parser parser)
            result (stream-parser "not json")]
        (is (nil? result)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.llm.interface-test)

  :leave-this-here)
