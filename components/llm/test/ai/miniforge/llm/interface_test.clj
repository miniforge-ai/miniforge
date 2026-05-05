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

(ns ai.miniforge.llm.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.miniforge.llm.interface :as llm]
            [ai.miniforge.llm.protocols.records.llm-client]
            [ai.miniforge.llm.protocols.impl.llm-client :as impl]
            [ai.miniforge.llm.progress-monitor :as pm]
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
      (is (nil? (get-in parsed [:usage :input-tokens])))))

  (testing "result event captures num_turns and top-level stop_reason"
    (let [line (json/generate-string
                 {:type "result"
                  :num_turns 42
                  :stop_reason "max_turns"
                  :result {:usage {:input_tokens 1 :output_tokens 2}}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= 42 (:num-turns parsed)))
      (is (= "max_turns" (:stop-reason parsed))))))

(deftest parse-claude-stream-assistant-stop-reason-test
  (testing "assistant message with text carries stop_reason"
    (let [line (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "text" :text "hello"}]
                            :stop_reason "end_turn"}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= "hello" (:delta parsed)))
      (is (= "end_turn" (:stop-reason parsed)))))

  (testing "assistant message with tool_use carries stop_reason"
    (let [line (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "tool_use" :name "Read"}]
                            :stop_reason "tool_use"}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:tool-use parsed)))
      (is (= ["Read"] (:tool-names parsed)))
      (is (= "tool_use" (:stop-reason parsed)))))

  (testing "assistant message with empty text + no tools + stop_reason still surfaces"
    (let [line (json/generate-string
                 {:type "assistant"
                  :message {:content [] :stop_reason "max_tokens"}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= "" (:delta parsed)))
      (is (= "max_tokens" (:stop-reason parsed)))))

  (testing "assistant message without stop_reason has no :stop-reason key"
    (let [line (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "text" :text "partial"}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= "partial" (:delta parsed)))
      (is (nil? (:stop-reason parsed))))))

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
                         :server "context" :tool "context_read"
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

(deftest parse-codex-stream-line-finish-reason-test
  (testing "turn.completed always sets :increment-turns true"
    (let [line (json/generate-string {:type "turn.completed"
                                      :usage {:input_tokens 10 :output_tokens 5}})
          parsed (impl/parse-codex-stream-line line)]
      (is (true? (:increment-turns parsed)))))

  (testing "finish_reason 'stop' normalizes to 'end_turn'"
    (let [line (json/generate-string {:type "turn.completed"
                                      :finish_reason "stop"
                                      :usage {:input_tokens 10 :output_tokens 5}})
          parsed (impl/parse-codex-stream-line line)]
      (is (= "end_turn" (:stop-reason parsed)))))

  (testing "finish_reason 'max_turns' passes through unchanged"
    (let [line (json/generate-string {:type "turn.completed"
                                      :finish_reason "max_turns"
                                      :usage {:input_tokens 10 :output_tokens 5}})
          parsed (impl/parse-codex-stream-line line)]
      (is (= "max_turns" (:stop-reason parsed)))))

  (testing "finish_reason 'length' normalizes to 'max_tokens'"
    (let [line (json/generate-string {:type "turn.completed"
                                      :finish_reason "length"
                                      :usage {:input_tokens 10 :output_tokens 5}})
          parsed (impl/parse-codex-stream-line line)]
      (is (= "max_tokens" (:stop-reason parsed)))))

  (testing "unknown finish_reason passes through as-is"
    (let [line (json/generate-string {:type "turn.completed"
                                      :finish_reason "something_new"
                                      :usage {:input_tokens 10 :output_tokens 5}})
          parsed (impl/parse-codex-stream-line line)]
      (is (= "something_new" (:stop-reason parsed)))))

  (testing "absent finish_reason produces no :stop-reason key"
    (let [line (json/generate-string {:type "turn.completed"
                                      :usage {:input_tokens 10 :output_tokens 5}})
          parsed (impl/parse-codex-stream-line line)]
      (is (nil? (:stop-reason parsed))))))

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
      (is (some? (:anomaly resp)))))

  (testing "blank successful output is classified as failure"
    (let [resp (impl/parse-cli-output "" 0)]
      (is (not (:success resp)))
      (is (= "empty_success_output" (get-in resp [:error :type])))
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
          tools (atom [])
          session-id (atom nil)
          stop-reason (atom nil)
          turns (atom nil)
          handler (impl/stream-with-parser
                    #'impl/parse-claude-stream-line
                    (fn [chunk] (swap! chunks conj chunk))
                    content
                    usage
                    cost
                    tools
                    session-id
                    stop-reason
                    turns)]
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

(deftest stream-parser-accumulates-stop-reason-and-turns-test
  (testing "latest stop_reason wins, num_turns captured from result event"
    (let [content (atom "")
          usage (atom nil)
          cost (atom nil)
          chunks (atom [])
          tools (atom [])
          session-id (atom nil)
          stop-reason (atom nil)
          turns (atom nil)
          handler (impl/stream-with-parser
                    #'impl/parse-claude-stream-line
                    (fn [chunk] (swap! chunks conj chunk))
                    content usage cost tools session-id stop-reason turns)]
      ;; First assistant message with tool_use (not the final stop)
      (handler (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "tool_use" :name "Grep"}]
                            :stop_reason "tool_use"}}))
      (is (= "tool_use" @stop-reason))
      ;; Final assistant message — end_turn should overwrite tool_use
      (handler (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "text" :text "done"}]
                            :stop_reason "end_turn"}}))
      (is (= "end_turn" @stop-reason))
      ;; Result event — top-level stop_reason + num_turns take precedence
      (handler (json/generate-string
                 {:type "result"
                  :num_turns 7
                  :stop_reason "end_turn"
                  :usage {:input_tokens 100 :output_tokens 50}}))
      (is (= "end_turn" @stop-reason))
      (is (= 7 @turns)))))

(deftest stream-parser-codex-increment-turns-test
  (testing "turn.completed events increment the turns accumulator"
    (let [content (atom "")
          usage (atom nil)
          cost (atom nil)
          chunks (atom [])
          tools (atom [])
          session-id (atom nil)
          stop-reason (atom nil)
          turns (atom nil)
          handler (impl/stream-with-parser
                    #'impl/parse-codex-stream-line
                    (fn [chunk] (swap! chunks conj chunk))
                    content usage cost tools session-id stop-reason turns)]
      ;; First turn.completed — turns goes from nil to 1
      (handler (json/generate-string
                 {:type "turn.completed"
                  :usage {:input_tokens 100 :output_tokens 20}}))
      (is (= 1 @turns))
      ;; Second turn.completed — turns increments to 2
      (handler (json/generate-string
                 {:type "turn.completed"
                  :usage {:input_tokens 200 :output_tokens 40}}))
      (is (= 2 @turns))))

  (testing "turn.completed with finish_reason sets stop-reason accumulator"
    (let [content (atom "")
          usage (atom nil)
          cost (atom nil)
          chunks (atom [])
          tools (atom [])
          session-id (atom nil)
          stop-reason (atom nil)
          turns (atom nil)
          handler (impl/stream-with-parser
                    #'impl/parse-codex-stream-line
                    (fn [chunk] (swap! chunks conj chunk))
                    content usage cost tools session-id stop-reason turns)]
      (handler (json/generate-string
                 {:type "turn.completed"
                  :finish_reason "stop"
                  :usage {:input_tokens 50 :output_tokens 10}}))
      (is (= "end_turn" @stop-reason))
      (is (= 1 @turns)))))

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
      (is (:success resp))))

  (testing "blank content in success-response returns error"
    (let [resp (impl/success-response "" 0)]
      (is (not (:success resp)))
      (is (= "empty_success_output" (get-in resp [:error :type])))))

  (testing "success-response preserves stderr for diagnostics"
    (let [resp (impl/success-response "ok" 0 "warning on stderr")]
      (is (:success resp))
      (is (= "warning on stderr" (:stderr resp))))))

(deftest rate-limited-streaming-success-response-test
  (testing "rate limit content in streaming-success-response returns error"
    (let [resp (impl/streaming-success-response
                 "You've hit your limit · resets 7pm (America/Los_Angeles)" 0 nil nil nil nil nil nil nil)]
      (is (not (:success resp)))
      (is (some? (:error resp)))))

  (testing "normal content in streaming-success-response returns success"
    (let [resp (impl/streaming-success-response "(defn foo [] 42)" 0 nil nil nil nil nil nil nil)]
      (is (:success resp))))

  (testing "stop-reason and num-turns flow through to success response"
    (let [resp (impl/streaming-success-response "ok" 0 {:input-tokens 1 :output-tokens 2}
                                                 nil "max_turns" 80 nil nil nil)]
      (is (:success resp))
      (is (= "max_turns" (:stop-reason resp)))
      (is (= 80 (:num-turns resp))))))

;------------------------------------------------------------------------------ Layer 4
;; Diagnostic metadata tests — tool-call-count and final-message-preview

(deftest streaming-success-response-diagnostic-test
  (testing "tool-call-count is included when provided"
    (let [resp (impl/streaming-success-response "hello" 0 nil nil nil nil 3 nil nil)]
      (is (:success resp))
      (is (= 3 (:tool-call-count resp)))))

  (testing "tool-call-count of 0 is still surfaced"
    (let [resp (impl/streaming-success-response "hello" 0 nil nil nil nil 0 nil nil)]
      (is (:success resp))
      (is (= 0 (:tool-call-count resp)))))

  (testing "final-message-preview is included for non-empty content"
    (let [resp (impl/streaming-success-response "hello" 0 nil nil nil nil nil "hello" nil)]
      (is (:success resp))
      (is (= "hello" (:final-message-preview resp)))))

  (testing "final-message-preview is absent when nil"
    (let [resp (impl/streaming-success-response "hello" 0 nil nil nil nil nil nil nil)]
      (is (:success resp))
      (is (nil? (:final-message-preview resp)))))

  (testing "all diagnostic fields coexist with existing fields"
    (let [resp (impl/streaming-success-response
                 "done" 0 {:input-tokens 10 :output-tokens 5}
                 0.002 "end_turn" 3 7 "preview text" "diagnostic stderr")]
      (is (:success resp))
      (is (= "done" (:content resp)))
      (is (= "end_turn" (:stop-reason resp)))
      (is (= 3 (:num-turns resp)))
      (is (= 7 (:tool-call-count resp)))
      (is (= "preview text" (:final-message-preview resp)))
      (is (= "diagnostic stderr" (:stderr resp)))
      (is (= 0.002 (:cost-usd resp))))))

(deftest streaming-error-response-diagnostic-test
  (testing "tool-call-count is surfaced on error responses"
    (let [resp (impl/streaming-error-response "" -1 "process died" nil nil nil 5 nil)]
      (is (not (:success resp)))
      (is (= 5 (:tool-call-count resp)))))

  (testing "final-message-preview is surfaced on error responses"
    (let [resp (impl/streaming-error-response "partial output" -1 "process died" nil nil nil nil "partial output")]
      (is (not (:success resp)))
      (is (= "partial output" (:final-message-preview resp)))))

  (testing "stop-reason and num-turns appear on error response top level"
    (let [resp (impl/streaming-error-response "" -1 "timed out"
                                              {:type :stream-idle :message "idle" :elapsed-ms 1000}
                                              "max_turns" 12 3 "last bit")]
      (is (not (:success resp)))
      (is (= "max_turns" (:stop-reason resp)))
      (is (= 12 (:num-turns resp)))
      (is (= 3 (:tool-call-count resp)))
      (is (= "last bit" (:final-message-preview resp))))))

(deftest process-stream-lines-eof-is-not-timeout-test
  (testing "clean EOF with no lines does not synthesize a stream-idle timeout"
    (let [reader (java.io.BufferedReader. (java.io.StringReader. ""))
          monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 1000
                    :max-total-ms 1000
                    :min-activity-interval-ms 1})
          result (impl/process-stream-lines reader monitor (fn [_] nil))]
      (is (= [] (:lines result)))
      (is (nil? (:timeout result))))))

(deftest process-stream-lines-honors-progress-monitor-while-waiting-for-output-test
  (testing "progress-monitor timeout can fire while stdout is idle and still open"
    (let [writer (java.io.PipedWriter.)
          reader (java.io.BufferedReader. (java.io.PipedReader. writer))
          calls (atom 0)
          timeout {:type :stagnation
                   :message "Backend preflight stalled"
                   :elapsed-ms 1234}
          monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 1000
                    :max-total-ms 1000
                    :min-activity-interval-ms 1})]
      (try
        (with-redefs [pm/check-timeout (fn [_]
                                         (when (>= (swap! calls inc) 3)
                                           timeout))]
          (let [result (impl/process-stream-lines reader monitor (fn [_] nil))]
            (is (= [] (:lines result)))
            (is (= timeout (:timeout result)))))
        (finally
          (.close writer))))))

(deftest message-preview-via-streaming-success-test
  (testing "short content is returned in full as preview"
    (let [short-content "short response"
          resp (impl/streaming-success-response short-content 0 nil nil nil nil 0 short-content nil)]
      (is (= short-content (:final-message-preview resp)))))

  (testing "long content preview contains only last 500 chars"
    (let [long-content (str/join (repeat 600 "x"))
          preview (subs long-content 100)  ; last 500 of 600
          resp (impl/streaming-success-response long-content 0 nil nil nil nil 0 preview nil)]
      (is (= 500 (count (:final-message-preview resp)))))))

(deftest complete-stream-impl-empty-stream-fallback-test
  (testing "streaming success with no parsed output falls back to non-streaming completion"
    (let [chunks (atom [])
          client (ai.miniforge.llm.protocols.records.llm-client/create-client
                  {:backend :claude
                   :stream-exec-fn (fn [_cmd _on-line _opts]
                                     {:out "" :err "stream stderr" :exit 0})
                   :exec-fn (fn [_cmd]
                              {:out "fallback answer" :err "" :exit 0})})
          resp (llm/complete-stream client {:prompt "test"} #(swap! chunks conj %))]
      (is (:success resp))
      (is (= "fallback answer" (:content resp)))
      (is (= [{:delta "fallback answer"
               :done? true
               :content "fallback answer"}]
             @chunks)))))

;------------------------------------------------------------------------------ Layer 5
;; Claude backend args-fn budget tests (PR #288)

(deftest claude-args-fn-budget-usd-test
  (let [args-fn (:args-fn (get impl/backends :claude))]

    (testing "budget-usd from request is used as --max-budget-usd value"
      (let [args (args-fn {:prompt "test" :budget-usd 2.50})]
        (is (some #{"--max-budget-usd"} args))
        (is (some #{"2.5"} args))))

    (testing "falls back to 0.10 when max-tokens set but no budget-usd"
      (let [args (args-fn {:prompt "test" :max-tokens 8000})]
        (is (some #{"--max-budget-usd"} args))
        (is (some #{"0.10"} args))))

    (testing "budget-usd takes priority over max-tokens fallback"
      (let [args (args-fn {:prompt "test" :max-tokens 8000 :budget-usd 5.0})]
        (is (some #{"5.0"} args))
        (is (not (some #{"0.10"} args)))))

    (testing "no budget flag when neither budget-usd nor max-tokens"
      (let [args (args-fn {:prompt "test"})]
        (is (not (some #{"--max-budget-usd"} args)))))

    (testing "mcp-config flag is included when provided"
      (let [args (args-fn {:prompt "test" :mcp-config "/tmp/mcp.json"
                           :budget-usd 2.50})]
        (is (some #{"--mcp-config"} args))
        (is (some #{"/tmp/mcp.json"} args))))

    (testing "max-turns flag is included when provided"
      (let [args (args-fn {:prompt "test" :max-turns 5 :budget-usd 1.0})]
        (is (some #{"--max-turns"} args))
        (is (some #{"5"} args))))

    (testing "max-turns flag omitted when not provided"
      (let [args (args-fn {:prompt "test" :budget-usd 1.0})]
        (is (not (some #{"--max-turns"} args)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.llm.interface-test)

  :leave-this-here)
