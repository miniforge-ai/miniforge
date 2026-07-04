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
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.llm.interface :as llm]
            [ai.miniforge.llm.messages :as msg]
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
                  :result "final text"
                  :usage {:input_tokens 1234
                          :output_tokens 567}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= "" (:delta parsed)))
      (is (true? (:done? parsed)))
      (is (= "final text" (:final-content parsed)))
      (is (= 1234 (get-in parsed [:usage :input-tokens])))
      (is (= 567 (get-in parsed [:usage :output-tokens])))))

  (testing "result event with no usage returns nil tokens"
    (let [line (json/generate-string {:type "result" :result {}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:done? parsed)))
      (is (nil? (:usage parsed)))))

  (testing "result event with usage map present but nil token fields omits :usage"
    (let [line (json/generate-string
                 {:type "result"
                  :usage {:input_tokens nil
                          :output_tokens nil}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:done? parsed)))
      (is (nil? (:usage parsed)))))

  (testing "result event captures num_turns and top-level stop_reason"
    (let [line (json/generate-string
                 {:type "result"
                  :num_turns 42
                  :stop_reason "max_turns"
                  :result {:usage {:input_tokens 1 :output_tokens 2}}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= 42 (:num-turns parsed)))
      (is (= "max_turns" (:stop-reason parsed))))))

(deftest parse-claude-stream-liveness-event-test
  (testing "rate_limit_event parses as a heartbeat — keeps stream supervision alive"
    ;; 2026-05-04 dogfood regression: parse-claude-stream-line returned nil
    ;; for rate_limit_event, so 60s of 'allowed' rate-limit notifications
    ;; (the only thing the CLI emits while Opus thinks on a heavy first
    ;; turn) refreshed nothing and stagnation killed an actively-working
    ;; planner with zero assistant output produced.
    (let [line (json/generate-string
                 {:type "rate_limit_event"
                  :rate_limit_info {:status "allowed"}})
          parsed (impl/parse-claude-stream-line line)]
      (is (some? parsed)
          "rate_limit_event must not parse to nil")
      (is (true? (:heartbeat parsed))
          "rate_limit_event must be a heartbeat so the progress monitor refreshes")
      (is (false? (:done? parsed)))))

  (testing "system init event parses as a heartbeat"
    (let [line (json/generate-string {:type "system" :subtype "init"})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:heartbeat parsed))
          "system init refreshes liveness once at the start of the stream")
      (is (false? (:done? parsed))))))

(deftest record-parsed-progress-heartbeat-liveness-test
  (testing "heartbeat-only Claude stream events are recorded as activity"
    ;; This guards the production regression path more directly than the
    ;; parser-shape test above: if parsing still succeeds but progress
    ;; supervision stops treating heartbeats as activity, these assertions
    ;; fail and catch the 60s false-timeout behavior.
    (doseq [[label line] [["rate_limit_event"
                           (json/generate-string
                            {:type "rate_limit_event"
                             :rate_limit_info {:status "allowed"}})]
                          ["system/init"
                           (json/generate-string
                            {:type "system" :subtype "init"})]]]
      (let [parsed (impl/parse-claude-stream-line line)
            monitor (pm/create-progress-monitor {:stagnation-threshold-ms 1000
                                                 :max-total-ms 1000
                                                 :min-activity-interval-ms 1})
            before-ts (:last-activity-at @monitor)]
        (is (true? (:heartbeat parsed))
            (str label " should parse as a heartbeat prerequisite"))
        (Thread/sleep 5)
        (#'impl/record-parsed-progress! monitor parsed (atom ""))
        ;; Specifically assert the activity timestamp moved — a weaker
        ;; (not= before @monitor) check passes even if heartbeats stop
        ;; refreshing :last-activity-at (e.g. if the code regresses to
        ;; record-chunk! which mutates other fields without updating
        ;; the timestamp the stagnation timer reads).
        (is (< before-ts (:last-activity-at @monitor))
            (str label " heartbeat must advance :last-activity-at"))
        (is (nil? (pm/check-timeout monitor))
            (str label " heartbeat must keep monitor below stagnation threshold"))))))

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
      (is (= "context_read" (:tool-name parsed)))
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
    (let [monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
          content (atom "")
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
                    monitor
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
                  :result "Hello"
                  :usage {:input_tokens 100
                          :output_tokens 50}
                  :total_cost_usd 0.0045}))
      (is (= {:input-tokens 100 :output-tokens 50} @usage))
      (is (= 0.0045 @cost)))))

(deftest stream-parser-recovers-result-only-content-test
  (testing "result-only content is recovered when no assistant delta arrived"
    (let [monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
          content (atom "")
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
                   monitor
                   content usage cost tools session-id stop-reason turns)]
      (handler (json/generate-string
                {:type "result"
                 :result "{\"ok\":true}"
                 :stop_reason "end_turn"
                 :usage {:input_tokens 9 :output_tokens 3}}))
      (is (= "{\"ok\":true}" @content))
      (is (= {:input-tokens 9 :output-tokens 3} @usage))
      (is (= "end_turn" @stop-reason))
      (is (= "{\"ok\":true}" (:content (last @chunks)))))))

(deftest stream-parser-accumulates-stop-reason-and-turns-test
  (testing "latest stop_reason wins, num_turns captured from result event"
    (let [monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
          content (atom "")
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
                    monitor
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
    (let [monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
          content (atom "")
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
                    monitor
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
    (let [monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
          content (atom "")
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
                    monitor
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
    (is (impl/rate-limited? (msg/t :rate-limit-test.system/claude-limit-la)))
    (is (impl/rate-limited? (msg/t :rate-limit-test.system/claude-limit-utc))))

  (testing "detects generic rate limit phrasing"
    (is (impl/rate-limited? (msg/t :rate-limit-test.system/generic-exceeded)))
    (is (impl/rate-limited? (msg/t :rate-limit-test.system/generic-limited)))
    (is (impl/rate-limited? (msg/t :rate-limit-test.system/http-429))))

  (testing "does not flag normal content"
    (is (not (impl/rate-limited? (msg/t :rate-limit-test.system/clojure-content))))
    (is (not (impl/rate-limited? (msg/t :rate-limit-test.system/normal-implementation))))
    (is (not (impl/rate-limited?
              (json/generate-string
               {:claims [{:claim (msg/t :rate-limit-test.system/waf-claim)
                          :snippet (msg/t :rate-limit-test.system/waf-snippet)
                          :confidence 1.0
                          :tags [(msg/t :rate-limit-test.system/security-tag)
                                 (msg/t :rate-limit-test.system/in-progress-tag)
                                 (msg/t :rate-limit-test.system/risk-tag)]}]}))))
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
    (let [resp (impl/streaming-error-response "" -1 "process died" "" nil nil nil 5 nil)]
      (is (not (:success resp)))
      (is (= 5 (:tool-call-count resp)))))

  (testing "final-message-preview is surfaced on error responses"
    (let [resp (impl/streaming-error-response "partial output" -1 "process died" "partial output" nil nil nil nil "partial output")]
      (is (not (:success resp)))
      (is (= "partial output" (:final-message-preview resp)))))

  (testing "stop-reason and num-turns appear on error response top level"
    (let [resp (impl/streaming-error-response "" -1 "timed out"
                                              ""
                                              {:type :stream-idle :message "idle" :elapsed-ms 1000}
                                              "max_turns" 12 3 "last bit")]
      (is (not (:success resp)))
      (is (= "max_turns" (:stop-reason resp)))
      (is (= 12 (:num-turns resp)))
      (is (= 3 (:tool-call-count resp)))
      (is (= "last bit" (:final-message-preview resp)))))

  (testing "raw stdout is preserved when parsed content is empty"
    (let [resp (impl/streaming-error-response "" -1 "timed out"
                                              "{\"type\":\"system\"}"
                                              {:type :stagnation :message "stalled" :elapsed-ms 1000}
                                              nil nil nil nil)]
      (is (not (:success resp)))
      (is (= "{\"type\":\"system\"}" (get-in resp [:error :raw-stdout])))))

  (testing "raw stdout becomes the message when stderr and parsed content are blank"
    (let [raw "{\"type\":\"error\",\"message\":\"model unavailable\"}"
          resp (impl/streaming-error-response "" 1 "" raw nil nil nil nil nil)]
      (is (not (:success resp)))
      (is (= raw (get-in resp [:error :message])))
      (is (= raw (get-in resp [:error :raw-stdout]))))))

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

(deftest process-stream-lines-read-failure-is-anomaly-data-test
  (testing "reader failures return canonical anomaly data instead of throwing"
    (let [reader (java.io.BufferedReader.
                  (proxy [java.io.Reader] []
                    (read [_cbuf _off _len]
                      (throw (java.io.IOException. "stream read failed")))
                    (close [] nil)))
          monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 1000
                    :max-total-ms 1000
                    :min-activity-interval-ms 1})
          result (impl/process-stream-lines reader monitor (fn [_] nil))
          stream-anomaly (:anomaly result)]
      (is (= [] (:lines result)))
      (is (nil? (:timeout result)))
      (is (anomaly/anomaly? stream-anomaly))
      (is (= :fault (:anomaly/type stream-anomaly)))
      (is (= "stream read failed" (:anomaly/message stream-anomaly))))))

(def ^:private start-stream-reader! #'impl/start-stream-reader!)
(def ^:private stop-stream-reader!  #'impl/stop-stream-reader!)

(defn- wait-until-blocked
  "Spin-wait until `^Thread t` reaches a parked/blocked thread state
   (WAITING/TIMED_WAITING/BLOCKED). Used by the clean-shutdown regression
   test to confirm the reader is actually blocked on `.put` BEFORE we
   call `stop-stream-reader!`, so the interrupt is guaranteed to fire
   the InterruptedException path (the bug under test). Returns true if
   the thread reached the target state inside `timeout-ms`, false on
   give-up."
  [^Thread t timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        target   #{Thread$State/WAITING Thread$State/TIMED_WAITING Thread$State/BLOCKED}]
    (loop []
      (cond
        (contains? target (.getState t)) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(deftest start-stream-reader!-clean-shutdown-prints-no-stderr-test
  ;; Regression: pre-fix the daemon's `(catch Exception e ...)` caught
  ;; the InterruptedException raised by `stop-stream-reader!`'s
  ;; interrupt, then tried to enqueue a `stream-read-failure` anomaly
  ;; via `.put` — which itself raised IE because the thread's interrupt
  ;; flag was still set, bubbling the second IE to the JVM default
  ;; handler and printing an ugly stack trace to stderr. The 2026-06-04
  ;; dogfood surfaced 18 of these across 20 sub-tasks (pure noise, zero
  ;; workflow impact).
  ;;
  ;; The fix: dedicated `(catch InterruptedException _)` for the clean
  ;; shutdown + inner try/catch around the anomaly enqueue.
  ;;
  ;; The test exercises `start-stream-reader!` / `stop-stream-reader!`
  ;; directly with a CAPACITY-1 queue so the reader's second `.put`
  ;; blocks deterministically (no `Thread/sleep`-based race). With the
  ;; queue full, the reader is guaranteed to be parked on `.put` when
  ;; `stop-stream-reader!` interrupts it.
  (testing "interrupting a reader blocked on a full queue does not print to stderr"
    (let [;; Bounded queue, capacity 1. Pre-filled so the reader's first
          ;; put blocks immediately.
          line-queue (java.util.concurrent.LinkedBlockingQueue. 1)
          _          (.put line-queue "filler")
          ;; PipedWriter/Reader with one line of content so the reader
          ;; has something to read and try to enqueue.
          writer (java.io.PipedWriter.)
          reader (java.io.BufferedReader. (java.io.PipedReader. writer))
          _ (.write writer "first-line\n")
          _ (.flush writer)
          stderr-capture (java.io.ByteArrayOutputStream.)
          original-err System/err
          captured-print-stream (java.io.PrintStream. stderr-capture true "UTF-8")]
      (try
        (System/setErr captured-print-stream)
        (let [reader-thread (start-stream-reader! reader line-queue)]
          (try
            (is (wait-until-blocked reader-thread 1000)
                "reader must reach a blocked state on .put before the interrupt fires")
            (stop-stream-reader! reader-thread reader)
            (finally
              (.join reader-thread 1000))))
        ;; Read with the same charset the PrintStream wrote.
        (let [captured (.toString stderr-capture "UTF-8")]
          (is (not (re-find #"InterruptedException" captured))
              "clean shutdown must not leak an IE stack trace to stderr"))
        (finally
          (System/setErr original-err)
          ;; Close the redirected stream — flushes any buffered bytes and
          ;; releases the underlying ByteArrayOutputStream from the
          ;; PrintStream's writer lock.
          (.close captured-print-stream)
          (.close writer))))))

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

(deftest stream-with-parser-records-tool-progress-test
  (testing "tool-use events reset semantic progress even without text deltas"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 1000
                    :max-total-ms 1000
                    :min-activity-interval-ms 1})
          chunks (atom [])
          accumulated-content (atom "")
          accumulated-usage (atom nil)
          accumulated-cost (atom nil)
          accumulated-tools (atom [])
          accumulated-session-id (atom nil)
          accumulated-stop-reason (atom nil)
          accumulated-turns (atom nil)
          parse-line (:stream-parser (get impl/backends :claude))
          on-line (impl/stream-with-parser parse-line
                                           #(swap! chunks conj %)
                                           monitor
                                           accumulated-content
                                           accumulated-usage
                                           accumulated-cost
                                           accumulated-tools
                                           accumulated-session-id
                                           accumulated-stop-reason
                                           accumulated-turns)
          before (:last-activity-at @monitor)]
      (Thread/sleep 5)
      (on-line "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"mcp__context__grep\"}],\"stop_reason\":\"tool_use\"}}")
      (is (= ["mcp__context__grep"] @accumulated-tools))
      (is (= "tool_use" @accumulated-stop-reason))
      (is (< before (:last-activity-at @monitor)))
      (is (= "" @accumulated-content))
      (is (= 1 (count @chunks))))))

(deftest stream-with-parser-records-liveness-progress-test
  ;; 2026-05-04 dogfood regression — Claude emits rate_limit_event
  ;; while the model is mid-think (no assistant chunks yet). PR #774
  ;; mapped these to nil, so they did not advance the activity
  ;; timestamp. Adaptive stagnation killed the planner at 60s with
  ;; zero output. This integration test exercises the same
  ;; stream-with-parser path as production and verifies the activity
  ;; timestamp moves forward — a parser-shape-only test cannot prove
  ;; the supervisor actually got the heartbeat.
  (let [setup (fn []
                (let [monitor (pm/create-progress-monitor
                               {:stagnation-threshold-ms 1000
                                :max-total-ms 1000
                                :min-activity-interval-ms 1})
                      parse-line (:stream-parser (get impl/backends :claude))
                      on-line (impl/stream-with-parser parse-line
                                                       (fn [_] nil)
                                                       monitor
                                                       (atom "")
                                                       (atom nil)
                                                       (atom nil)
                                                       (atom [])
                                                       (atom nil)
                                                       (atom nil)
                                                       (atom nil))]
                  {:monitor monitor :on-line on-line}))]
    (testing "rate_limit_event advances the supervisor's activity timestamp"
      (let [{:keys [monitor on-line]} (setup)
            before (:last-activity-at @monitor)]
        (Thread/sleep 5)
        (on-line (json/generate-string
                   {:type "rate_limit_event"
                    :rate_limit_info {:status "allowed"}}))
        (is (< before (:last-activity-at @monitor))
            "rate_limit_event must keep the progress monitor alive")))

    (testing "system init event advances the supervisor's activity timestamp"
      (let [{:keys [monitor on-line]} (setup)
            before (:last-activity-at @monitor)]
        (Thread/sleep 5)
        (on-line (json/generate-string {:type "system" :subtype "init"}))
        (is (< before (:last-activity-at @monitor))
            "system init must refresh liveness once at stream start")))))

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
                   :exec-fn (fn
                              ([_cmd]      {:out "fallback answer" :err "" :exit 0})
                              ([_cmd _opts] {:out "fallback answer" :err "" :exit 0}))})
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

;------------------------------------------------------------------------------ Layer 5
;; Claude backend prompt delivery (argv vs stdin) — argv-overflow guard
;;
;; The Stage 3 dogfood (2026-05-07) hit "Argument list too long" because
;; the planner's system-prompt + spec text + existing-files context
;; pushed the argv past POSIX ARG_MAX. The :claude backend now declares
;; :prompt-via :stdin and the args-fn omits the positional prompt while
;; adding --input-format text. These tests lock that contract.

(def ^:private sample-prompt
  "Synthetic prompt used to assert the prompt content's *placement*
   (argv vs not-argv). Same shape (string) as a real planner prompt;
   size is small because the assertions hinge on identity-of-content,
   not length."
  "this should arrive on stdin not in argv")

(deftest claude-args-fn-prompt-via-argv-default-test
  (testing "prompt-via defaults to :argv — prompt is the last argv element"
    (let [args-fn (:args-fn (get impl/backends :claude))
          args (args-fn {:prompt sample-prompt})]
      (is (= sample-prompt (last args))
          "prompt is conjoined as positional argv arg")
      (is (not (some #{"--input-format"} args))
          "no --input-format flag in argv mode"))))

(deftest claude-args-fn-prompt-via-argv-explicit-test
  (testing "prompt-via :argv (explicit) keeps current argv shape"
    (let [args-fn (:args-fn (get impl/backends :claude))
          args (args-fn {:prompt sample-prompt :prompt-via :argv})]
      (is (= sample-prompt (last args))
          "prompt is conjoined as positional argv arg")
      (is (not (some #{"--input-format"} args))))))

(deftest claude-args-fn-prompt-via-stdin-omits-argv-test
  (testing "prompt-via :stdin drops the positional prompt"
    (let [args-fn (:args-fn (get impl/backends :claude))
          args (args-fn {:prompt sample-prompt :prompt-via :stdin})]
      (is (not (some #{sample-prompt} args))
          "prompt content must NOT appear in argv when :prompt-via :stdin")
      (is (some #{"--input-format"} args)
          "--input-format text flag must be present so claude reads stdin")
      (is (= "text" (nth args (inc (.indexOf args "--input-format"))))
          "--input-format value is 'text'"))))

(deftest claude-backend-declares-prompt-via-stdin-test
  (testing ":claude backend config declares :prompt-via :stdin"
    (is (= :stdin (:prompt-via (get impl/backends :claude)))
        "claude must default to stdin to avoid POSIX ARG_MAX overflow")))

(deftest cli-backends-declare-safe-prompt-delivery-test
  (testing "codex uses stdin; other non-Claude CLI backends remain argv"
    (is (= :stdin (:prompt-via (get impl/backends :codex))))
    (is (= :argv (:prompt-via (get impl/backends :cursor))))
    (is (= :argv (:prompt-via (get impl/backends :opencode))))
    (is (= :argv (:prompt-via (get impl/backends :echo))))))

;------------------------------------------------------------------------------ Layer 5
;; default-exec-fn / stream-exec-fn — :stdin opt is piped to the subprocess
;;
;; Use `cat` as the subprocess: stdin is echoed verbatim to stdout, so we
;; can assert the bytes the exec layer wrote without depending on a
;; real LLM CLI.

(deftest default-exec-fn-pipes-stdin-test
  (testing ":stdin opt is written to subprocess stdin"
    (let [{:keys [out exit]} (impl/default-exec-fn ["cat"] {:stdin "hello stdin"})]
      (is (zero? exit))
      (is (= "hello stdin" out)))))

(deftest default-exec-fn-no-stdin-default-test
  (testing "no :stdin opt → empty stdin (legacy behavior)"
    (let [{:keys [out exit]} (impl/default-exec-fn ["cat"])]
      (is (zero? exit))
      (is (= "" out)))))

(deftest default-exec-fn-honors-workdir-test
  (testing ":workdir opt becomes the subprocess working directory"
    (let [workdir (.getCanonicalPath (java.io.File. (System/getProperty "java.io.tmpdir")))
          {:keys [out exit]} (impl/default-exec-fn ["pwd"] {:workdir workdir})
          actual (.getCanonicalPath (java.io.File. (str/trim out)))]
      (is (zero? exit))
      (is (= workdir actual)))))

(deftest read-process-stream-error-is-string-test
  (testing "reader exceptions never publish nil output"
    (let [stream (proxy [java.io.InputStream] []
                   (read
                     ([]
                      (throw (Exception.)))
                     ([buffer]
                      (throw (Exception.)))
                     ([buffer offset length]
                      (throw (Exception.)))))
          output (atom nil)
          reader (#'impl/read-process-stream! "llm-test-reader" stream output)]
      (.join ^Thread reader 1000)
      (is (string? @output))
      (is (seq @output)))))

(deftest stop-capture-process-waits-after-destroy-test
  (testing "capture timeout waits again after forcible destroy before returning"
    (let [destroyed? (atom false)
          wait-calls (atom [])
          process (proxy [Process] []
                    (destroy []
                      (reset! destroyed? true))
                    (exitValue []
                      0)
                    (getErrorStream []
                      (java.io.ByteArrayInputStream. (byte-array 0)))
                    (getInputStream []
                      (java.io.ByteArrayInputStream. (byte-array 0)))
                    (getOutputStream []
                      (java.io.ByteArrayOutputStream.))
                    (waitFor []
                      0))]
      (with-redefs-fn {#'impl/post-kill-join-timeout-ms (constantly 7)
                       #'impl/wait-for-stream-process
                       (fn [_ timeout-ms]
                         (swap! wait-calls conj timeout-ms)
                         (if (= 1 (count @wait-calls))
                           {:exit -1 :err "Process timed out"}
                           {:exit 0 :err ""}))}
        (fn []
          (let [result (#'impl/stop-capture-process!
                        {:process process
                         :stdout (atom nil)
                         :stderr (atom nil)}
                        5)]
            (is @destroyed?)
            (is (= [5 7] @wait-calls))
            (is (= 0 (:exit result)))
            (is (= "" (:out result)))
            (is (= "" (:err result)))))))))

(deftest complete-impl-passes-workdir-to-exec-fn-test
  (testing "non-streaming CLI completion runs in the requested workdir"
    (let [seen-opts (atom nil)
          exec-fn   (fn
                      ([_cmd] {:out "ok" :err "" :exit 0})
                      ([_cmd opts]
                       (reset! seen-opts opts)
                       {:out "ok" :err "" :exit 0}))
          client    (ai.miniforge.llm.protocols.records.llm-client/create-client
                     {:backend :echo :exec-fn exec-fn})
          resp      (llm/complete client {:prompt "hello" :workdir "/tmp/task-worktree"})]
      (is (llm/success? resp))
      (is (= "/tmp/task-worktree" (:workdir @seen-opts))
          "fallback/non-streaming calls must not run in the host checkout"))))

(deftest legacy-1-arity-exec-fn-survives-stdin-path-test
  (testing "1-arity user-supplied :exec-fn keeps working when impl invokes 2-arity"
    ;; Backward-compat guard: PR #798 review pointed out that callers
    ;; predating :prompt-via supplied 1-arity exec-fns. The :claude
    ;; backend now invokes 2-arity (it declares :prompt-via :stdin).
    ;; create-client wraps user fns through normalize-exec-fn so the
    ;; legacy contract still works.
    (let [seen (atom nil)
          legacy-1-arity (fn [cmd]
                           (reset! seen cmd)
                           {:out "ok" :err "" :exit 0})
          client (ai.miniforge.llm.protocols.records.llm-client/create-client
                  {:backend :claude :exec-fn legacy-1-arity})
          resp (llm/complete client {:prompt "hello"})]
      (is (llm/success? resp)
          "complete succeeds even though impl tried 2-arity first")
      (is (some? @seen)
          "legacy 1-arity fn was eventually invoked"))))

(deftest normalize-exec-fn-passes-opts-to-2-arity-fn-test
  (testing "2-arity user-supplied exec-fn receives opts unchanged"
    (let [seen-opts (atom nil)
          two-arity (fn
                      ([cmd] {:out "" :err "" :exit 0 :seen-cmd cmd})
                      ([_cmd opts]
                       (reset! seen-opts opts)
                       {:out "ok" :err "" :exit 0}))
          wrapped (impl/normalize-exec-fn two-arity)]
      (wrapped ["claude" "-p"] {:stdin "the-prompt"})
      (is (= {:stdin "the-prompt"} @seen-opts)
          "opts must reach a 2-arity-capable fn unchanged"))))

;------------------------------------------------------------------------------ Layer 5
;; Keepalive — decouples stagnation from the CLI's emission cadence
;;
;; Stage-3 dogfood (2026-05-07) failed at the planner with
;; "Stagnation timeout: no progress for 180047ms". Diagnosis: claude
;; CLI in `--input-format text` mode does not emit rate_limit_event
;; during the model's think phase, so the stream-heartbeat-based
;; stagnation guard has no signal during legitimate thinking.
;;
;; Fix: pm/start-keepalive! daemon thread that pings the monitor while
;; the JVM-side reader is alive. These tests lock both halves of the
;; contract — the bug pattern (silence => stagnation) and the fix
;; (keepalive masks silence).

(deftest stagnation-fires-on-silence-without-keepalive-test
  (testing "without keepalive, a silent monitor stagnates after the threshold"
    ;; Bug demonstration: this is the dogfood failure mode in unit form.
    ;; record-activity! once at start, then silence — the timer expires.
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 80
                    :max-total-ms            5000
                    :min-activity-interval-ms 1})]
      (pm/record-activity! monitor :stream-init)
      (Thread/sleep 200)
      (let [timeout (pm/check-timeout monitor)]
        (is (= :stagnation (:type timeout))
            "silence beyond stagnation-threshold-ms ⇒ stagnation fires")))))

(deftest keepalive-prevents-stagnation-during-silence-test
  (testing "keepalive thread refreshes the monitor across silent periods"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 80
                    :max-total-ms            5000
                    :min-activity-interval-ms 1})
          stop! (pm/start-keepalive! monitor 20)]
      (try
        (pm/record-activity! monitor :stream-init)
        (Thread/sleep 200)
        (is (nil? (pm/check-timeout monitor))
            "keepalive refreshes ⇒ stagnation timer never expires")
        (finally
          (stop!))))))

(deftest keepalive-stop-fn-halts-the-thread-test
  (testing "stop! returned by start-keepalive! halts further refreshes"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 80
                    :max-total-ms            5000
                    :min-activity-interval-ms 1})
          stop! (pm/start-keepalive! monitor 20)]
      (pm/record-activity! monitor :stream-init)
      (Thread/sleep 50)
      (stop!)
      ;; Allow any in-flight tick to settle, then sleep past the threshold.
      (Thread/sleep 200)
      (is (= :stagnation (:type (pm/check-timeout monitor)))
          "after stop!, silence stagnates as expected"))))

(deftest keepalive-rejects-non-positive-interval-test
  (testing "start-keepalive! refuses non-positive interval-ms"
    ;; Without validation Thread/sleep would throw IllegalArgumentException
    ;; deep in the worker thread; assert + AssertionError fails fast at the
    ;; call site (PR #806 Copilot review).
    (let [monitor (pm/create-progress-monitor {:stagnation-threshold-ms 1000})]
      (is (thrown? AssertionError (pm/start-keepalive! monitor 0))
          "0 interval rejected")
      (is (thrown? AssertionError (pm/start-keepalive! monitor -10))
          "negative interval rejected")
      (is (thrown? AssertionError (pm/start-keepalive! monitor 1.5))
          "non-integer interval rejected"))))

(deftest keepalive-stop-is-definitive-test
  (testing "after stop!, no further activity ticks land"
    ;; Race-window guard: stop! interrupts the thread + joins so the
    ;; worker has exited (or is about to exit on the running? check)
    ;; before stop! returns. PR #806 Copilot review.
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 5000
                    :max-total-ms            60000
                    :min-activity-interval-ms 1})
          stop! (pm/start-keepalive! monitor 5)]
      ;; Let several ticks land
      (Thread/sleep 50)
      (let [chunks-before-stop (:chunk-count @monitor)]
        (stop!)
        ;; Sleep long enough that further ticks WOULD land if the
        ;; worker were still running (~10× the interval).
        (Thread/sleep 100)
        (let [chunks-after-stop (:chunk-count @monitor)
              extra-ticks       (- chunks-after-stop chunks-before-stop)]
          (is (<= extra-ticks 1)
              (str "at most one in-flight tick may land after stop!, got "
                   extra-ticks)))))))

(deftest keepalive-does-not-mask-hard-limit-test
  (testing "keepalive only refreshes stagnation; max-total-ms still fires"
    ;; Wedge backstop: the keepalive is a per-tick refresh of
    ;; last-activity-at, but :max-total-ms tracks elapsed-since-start
    ;; independently. A run that legitimately exceeds max-total-ms
    ;; must still time out — the keepalive cannot mask it.
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms 50000
                    :max-total-ms            120
                    :min-activity-interval-ms 1})
          stop! (pm/start-keepalive! monitor 20)]
      (try
        (pm/record-activity! monitor :stream-init)
        (Thread/sleep 200)
        (let [timeout (pm/check-timeout monitor)]
          (is (= :hard-limit (:type timeout))
              "max-total-ms fires even with keepalive active"))
        (finally
          (stop!))))))

(deftest stream-exec-fn-pipes-stdin-test
  (testing ":stdin opt is written to subprocess stdin in streaming path"
    (let [seen (atom [])
          handler (fn [line] (swap! seen conj line))
          monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
          {:keys [exit]} (impl/stream-exec-fn ["cat"] handler
                                              {:stdin "line1\nline2\n"
                                               :progress-monitor monitor})]
      (is (zero? exit))
      (is (= ["line1" "line2"] @seen)
          "subprocess saw stdin content split by line"))))

(defn- fake-stream-process
  "Stub for `#'impl/start-stream-process!`. Returns a map matching the
   shape `stream-exec-fn` consumes, with a `Process` proxy whose
   stdout is `input-stream`. `.destroyForcibly` closes the supplied
   `on-destroy!` closure (used for tests that need to release a
   PipedInputStream so the consumer's reader sees EOF).

   Cross-platform replacement for `[\"sleep\" \"30\"]` / `[\"echo\"
   \"hello\"]` subprocesses (Copilot review on PR #1053). No real
   subprocess starts; portable to Windows / minimal containers."
  [^java.io.InputStream input-stream on-destroy!]
  (let [destroyed? (atom false)
        ;; `waitFor(long, TimeUnit)` is the overload `wait-for-stream-
        ;; process` calls; the 0-arg `waitFor()` is declared for
        ;; completeness but never reached on this code path.
        process    (proxy [java.lang.Process] []
                     (getInputStream  [] input-stream)
                     (getOutputStream [] (java.io.ByteArrayOutputStream.))
                     (getErrorStream  [] (java.io.ByteArrayInputStream. (byte-array 0)))
                     (destroyForcibly []
                       (reset! destroyed? true)
                       (on-destroy!)
                       this)
                     (isAlive   [] (not @destroyed?))
                     (waitFor
                       ([] 0)
                       ([_timeout _unit] true))
                     (exitValue [] (if @destroyed? -1 0)))]
    {:process       process
     :stderr        (atom "")
     :stdin-thread  nil
     :stderr-thread nil}))

(defn- fake-blocking-stream-process
  "Variant of `fake-stream-process` whose stdout never produces a line
   (PipedInputStream connected to a never-written PipedOutputStream).
   `.destroyForcibly` closes the pipe so the reader unblocks with EOF."
  []
  (let [piped-out (java.io.PipedOutputStream.)
        piped-in  (java.io.PipedInputStream. piped-out)]
    (fake-stream-process piped-in
                         (fn [] (try (.close piped-out) (catch Exception _))))))

(defn- fake-eof-stream-process
  "Variant of `fake-stream-process` whose stdout is immediately empty,
   so `process-stream-lines` returns cleanly. Used by the no-monitor
   test."
  []
  (fake-stream-process (java.io.ByteArrayInputStream. (byte-array 0))
                       (fn [])))

(deftest stream-exec-fn-network-drop-returns-network-drop-timeout-test
  ;; PR-B integration test: when the network-monitor's probe-fn reports
  ;; sustained failure, `stream-exec-fn` must (a) kill the subprocess
  ;; so the consumer loop unblocks and (b) surface the network-drop
  ;; reason as the result's `:timeout` envelope. PR-C will key off the
  ;; `:type :network-drop` to schedule the auto-resume.
  (testing "sustained probe failure surfaces a :network-drop timeout result"
    ;; Stubbed subprocess (see `fake-blocking-stream-process`) — never
    ;; produces output until the network monitor's `.destroyForcibly`
    ;; closes the pipe. With a stub probe-fn returning false, the
    ;; monitor fires within ~30ms (10ms interval × 3 strikes) and
    ;; forces stream-exec-fn to return. No real subprocess; portable.
    (with-redefs [impl/start-stream-process! (fn [_cmd _opts]
                                               (fake-blocking-stream-process))]
      (let [handler (fn [_line] nil)
            monitor (pm/create-progress-monitor {:min-activity-interval-ms 1
                                                 :stagnation-threshold-ms  120000
                                                 :max-total-ms             120000})
            result  (impl/stream-exec-fn
                      ["unused-because-stubbed"]
                      handler
                      {:progress-monitor monitor
                       :backend-key      :claude
                       :network-monitor-opts
                       {:probe-interval-ms 10
                        :failure-threshold 3
                        :probe-fn          (constantly false)}})
            timeout (:timeout result)]
        (is (some? timeout)
            "result must carry a :timeout envelope when the monitor fires")
        (is (= :network-drop (:type timeout)))
        (is (= :claude (:backend-key timeout)))
        (is (re-find #"Network drop" (:message timeout))
            ":message identifies the drop class for downstream log readers")))))

(deftest stream-exec-fn-omits-network-monitor-when-no-backend-key-test
  ;; Backwards compat: legacy callers (synthetic-stream tests, the
  ;; `:echo` test backend) supply no `:backend-key`. The network
  ;; monitor must stay dormant so they keep working unchanged.
  (testing "without :backend-key, no probe traffic is generated"
    (with-redefs [impl/start-stream-process! (fn [_cmd _opts]
                                               (fake-eof-stream-process))]
      (let [probe-calls (atom 0)
            handler (fn [_line] nil)
            monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
            result  (impl/stream-exec-fn
                      ["unused-because-stubbed"]
                      handler
                      {:progress-monitor monitor
                       ;; No :backend-key. Even if the test caller
                       ;; mistakenly supplies network-monitor-opts (e.g.
                       ;; via a stale fixture), the monitor must not
                       ;; start.
                       :network-monitor-opts
                       {:probe-fn (fn [_probe-url]
                                    (swap! probe-calls inc)
                                    false)}})]
        (is (zero? @probe-calls)
            "probe-fn must not be called when :backend-key is absent")
        (is (nil? (:timeout result))
            "process completed without timeout")))))

;------------------------------------------------------------------------------ Layer 6
;; Rich tool-event chunks — :tool-call-id/:tool-input on tool_use,
;; :tool-result chunks from tool_result content blocks

(deftest parse-claude-stream-tool-use-enriched-test
  (testing "tool_use block with id and input yields :tool-call-id and :tool-input"
    (let [line   (json/generate-string
                   {:type "assistant"
                    :message {:content [{:type  "tool_use"
                                         :id    "toolu_01abc"
                                         :name  "Read"
                                         :input {:path "/tmp/foo.txt"}}]
                              :stop_reason "tool_use"}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:tool-use parsed)))
      (is (= ["Read"] (vec (:tool-names parsed))))
      (is (= "toolu_01abc" (:tool-call-id parsed)))
      (is (= {:path "/tmp/foo.txt"} (:tool-input parsed)))
      (is (= "tool_use" (:stop-reason parsed)))))

  (testing "tool_use block without :id does not add :tool-call-id key"
    (let [line   (json/generate-string
                   {:type "assistant"
                    :message {:content [{:type "tool_use" :name "Read"}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:tool-use parsed)))
      (is (nil? (:tool-call-id parsed)))))

  (testing "tool_use block without :input does not add :tool-input key"
    (let [line   (json/generate-string
                   {:type "assistant"
                    :message {:content [{:type "tool_use" :id "toolu_01abc" :name "Read"}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (= "toolu_01abc" (:tool-call-id parsed)))
      (is (nil? (:tool-input parsed)))))

  (testing "existing :tool-names and :tool-use keys are unchanged"
    (let [line   (json/generate-string
                   {:type "assistant"
                    :message {:content [{:type "tool_use" :id "t1" :name "Grep"
                                         :input {:pattern "foo"}}
                                        {:type "tool_use" :id "t2" :name "Read"
                                         :input {:path "/bar"}}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:tool-use parsed)))
      (is (= #{"Grep" "Read"} (set (:tool-names parsed))))
      ;; New keys come from the FIRST tool_use block only
      (is (= "t1" (:tool-call-id parsed)))
      (is (= {:pattern "foo"} (:tool-input parsed))))))

(deftest parse-claude-stream-tool-result-test
  (testing "user message with tool_result block emits :tool-result chunk"
    (let [line   (json/generate-string
                   {:type "user"
                    :message {:role    "user"
                              :content [{:type        "tool_result"
                                         :tool_use_id "toolu_01abc"
                                         :content     "file contents here"
                                         :is_error    false}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:tool-result parsed)))
      (is (= "" (:delta parsed)))
      (is (false? (:done? parsed)))
      (is (= "toolu_01abc" (:tool-call-id parsed)))
      (is (= "file contents here" (:tool-output parsed)))
      (is (false? (:tool-error? parsed)))))

  (testing "tool_result with is_error true sets :tool-error? true"
    (let [line   (json/generate-string
                   {:type "user"
                    :message {:content [{:type        "tool_result"
                                         :tool_use_id "toolu_err"
                                         :content     "No such file"
                                         :is_error    true}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:tool-result parsed)))
      (is (true? (:tool-error? parsed)))
      (is (= "toolu_err" (:tool-call-id parsed)))
      (is (= "No such file" (:tool-output parsed)))))

  (testing "tool_result with vector content joins text blocks"
    (let [line   (json/generate-string
                   {:type "user"
                    :message {:content [{:type    "tool_result"
                                         :tool_use_id "toolu_vec"
                                         :content [{:type "text" :text "line one"}
                                                   {:type "text" :text "line two"}]}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:tool-result parsed)))
      (is (= "line one\nline two" (:tool-output parsed)))))

  (testing "user message without tool_result blocks falls through to heartbeat"
    (let [line   (json/generate-string
                   {:type "user"
                    :message {:content [{:type "text" :text "hello"}]}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:heartbeat parsed)))
      (is (nil? (:tool-result parsed)))))

  (testing "user message with empty content falls through to heartbeat"
    (let [line   (json/generate-string
                   {:type "user" :message {:content []}})
          parsed (impl/parse-claude-stream-line line)]
      (is (true? (:heartbeat parsed)))
      (is (nil? (:tool-result parsed))))))

(deftest parse-codex-stream-tool-result-test
  (testing "mcp_tool_result item emits :tool-result chunk"
    (let [line   (json/generate-string
                   {:type "item.completed"
                    :item {:type         "mcp_tool_result"
                           :tool_call_id "call_abc"
                           :output       "tool output text"
                           :is_error     false}})
          parsed (impl/parse-codex-stream-line line)]
      (is (true? (:tool-result parsed)))
      (is (= "" (:delta parsed)))
      (is (false? (:done? parsed)))
      (is (= "call_abc" (:tool-call-id parsed)))
      (is (= "tool output text" (:tool-output parsed)))
      (is (false? (:tool-error? parsed)))))

  (testing "mcp_tool_result with is_error true sets :tool-error? true"
    (let [line   (json/generate-string
                   {:type "item.completed"
                    :item {:type         "mcp_tool_result"
                           :tool_call_id "call_err"
                           :output       "Error: not found"
                           :is_error     true}})
          parsed (impl/parse-codex-stream-line line)]
      (is (true? (:tool-result parsed)))
      (is (true? (:tool-error? parsed)))))

  (testing "mcp_tool_result falls back to :id when :tool_call_id absent"
    (let [line   (json/generate-string
                   {:type "item.completed"
                    :item {:type   "mcp_tool_result"
                           :id     "item_xyz"
                           :output "output text"}})
          parsed (impl/parse-codex-stream-line line)]
      (is (true? (:tool-result parsed)))
      (is (= "item_xyz" (:tool-call-id parsed)))
      (is (= "output text" (:tool-output parsed)))))

  (testing "native tool_result item emits :tool-result chunk"
    (let [line   (json/generate-string
                   {:type "item.completed"
                    :item {:type   "tool_result"
                           :id     "native_call"
                           :output "native output"}})
          parsed (impl/parse-codex-stream-line line)]
      (is (true? (:tool-result parsed)))
      (is (= "native_call" (:tool-call-id parsed)))
      (is (= "native output" (:tool-output parsed))))))

(deftest stream-with-parser-tool-result-test
  (testing ":tool-result chunks forwarded via on-chunk without accumulating content"
    (let [monitor (pm/create-progress-monitor {:min-activity-interval-ms 1})
          chunks  (atom [])
          content (atom "")
          handler (impl/stream-with-parser
                    #'impl/parse-claude-stream-line
                    (fn [chunk] (swap! chunks conj chunk))
                    monitor
                    content
                    (atom nil) (atom nil) (atom []) (atom nil) (atom nil) (atom nil))]
      ;; Text delta so accumulated-content is non-blank
      (handler (json/generate-string
                 {:type "assistant"
                  :message {:content [{:type "text" :text "Analyzing..."}]}}))
      ;; Tool-result event (user message with tool_result block)
      (handler (json/generate-string
                 {:type "user"
                  :message {:content [{:type        "tool_result"
                                       :tool_use_id "toolu_01abc"
                                       :content     "file line 1\nfile line 2"
                                       :is_error    false}]}}))
      ;; accumulated-content must not change on tool-result events
      (is (= "Analyzing..." @content)
          "accumulated content must not change on :tool-result events")
      ;; on-chunk must fire for both events
      (is (= 2 (count @chunks))
          "two chunks: text delta + tool-result")
      (let [tr (last @chunks)]
        (is (true? (:tool-result tr)))
        (is (= "toolu_01abc" (:tool-call-id tr)))
        (is (= "file line 1\nfile line 2" (:tool-output tr)))
        (is (= "Analyzing..." (:content tr))
            ":content carries accumulated text at the time of the tool-result"))))

  (testing ":tool-result chunk advances progress-monitor activity timestamp"
    (let [monitor (pm/create-progress-monitor
                   {:stagnation-threshold-ms  1000
                    :max-total-ms             1000
                    :min-activity-interval-ms 1})
          handler (impl/stream-with-parser
                    #'impl/parse-claude-stream-line
                    (fn [_] nil)
                    monitor
                    (atom "")
                    (atom nil) (atom nil) (atom []) (atom nil) (atom nil) (atom nil))
          before  (:last-activity-at @monitor)]
      (Thread/sleep 5)
      (handler (json/generate-string
                 {:type "user"
                  :message {:content [{:type        "tool_result"
                                       :tool_use_id "toolu_ping"
                                       :content     "pong"
                                       :is_error    false}]}}))
      (is (< before (:last-activity-at @monitor))
          ":tool-result must advance :last-activity-at to keep monitor alive"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.llm.interface-test)

  :leave-this-here)
