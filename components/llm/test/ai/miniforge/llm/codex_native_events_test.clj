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
(ns ai.miniforge.llm.codex-native-events-test
  "The native Codex event stream (`codex exec --json`) as the workflow sees
   it: tool calls the session ran and the usage it reported.

   The transcript follows the `ThreadEvent` / `ThreadItem` serde shapes in
   openai/codex codex-rs/exec/src/exec_events.rs at commit
   c90354172c5c49535b122443e9a8ed52c2233b80. It was written from that
   source; no Codex CLI ran to produce it."
  (:require
   [clojure.test :refer [deftest testing is]]
   [cheshire.core :as json]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.protocols.records.llm-client :as records]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} hello-run-events
  "One `codex exec` turn: list the repo, add hello.py, run it, read a file
   through the MCP context server, then answer."
  [{:type "thread.started" :thread_id "0199a213-81c0-7800-8aa1-bbab2a035a53"}
   {:type "turn.started"}
   {:type "item.completed"
    :item {:id "item_0" :type "reasoning" :text "**Listing the repository**"}}
   {:type "item.started"
    :item {:id "item_1" :type "command_execution" :command "bash -lc ls"
           :aggregated_output "" :exit_code nil :status "in_progress"}}
   {:type "item.completed"
    :item {:id "item_1" :type "command_execution" :command "bash -lc ls"
           :aggregated_output "README.md\n" :exit_code 0 :status "completed"}}
   {:type "item.completed"
    :item {:id "item_2" :type "file_change"
           :changes [{:path "hello.py" :kind "add"}] :status "completed"}}
   {:type "item.started"
    :item {:id "item_3" :type "command_execution" :command "bash -lc 'python3 hello.py'"
           :aggregated_output "" :exit_code nil :status "in_progress"}}
   {:type "item.completed"
    :item {:id "item_3" :type "command_execution" :command "bash -lc 'python3 hello.py'"
           :aggregated_output "hello\n" :exit_code 0 :status "completed"}}
   {:type "item.started"
    :item {:id "item_4" :type "mcp_tool_call" :server "context" :tool "context_read"
           :arguments {:path "README.md"} :result nil :error nil :status "in_progress"}}
   {:type "item.completed"
    :item {:id "item_4" :type "mcp_tool_call" :server "context" :tool "context_read"
           :arguments {:path "README.md"}
           :result {:content [{:type "text" :text "# demo"}] :structured_content nil}
           :error nil :status "completed"}}
   {:type "item.started"
    :item {:id "item_5" :type "todo_list"
           :items [{:text "Add hello.py" :completed false}]}}
   {:type "item.completed"
    :item {:id "item_6" :type "agent_message" :text "Added hello.py and ran it."}}
   {:type "item.completed"
    :item {:id "item_5" :type "todo_list"
           :items [{:text "Add hello.py" :completed true}]}}
   {:type "turn.completed"
    :usage {:input_tokens 24763 :cached_input_tokens 24448 :cache_write_input_tokens 0
            :output_tokens 122 :reasoning_output_tokens 64}}])

(defn ^{:stratum 0} parse-event
  [event]
  (impl/parse-codex-stream-line (json/generate-string event)))

(defn ^{:stratum 0} streamed-codex-response
  "Run a Codex client whose CLI emits `events`, then exits with `exit`."
  [events exit]
  (let [client (records/create-client
                {:backend :codex
                 :stream-exec-fn (fn [_cmd on-line _opts]
                                   (doseq [event events]
                                     (on-line (json/generate-string event)))
                                   {:out "" :err "" :exit exit})})]
    (llm/complete-stream client {:prompt "Add hello.py and run it."} (fn [_] nil))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} native-tool-items-parse-as-tool-calls-test
  (testing "each completed native tool item is one tool call, named by its type"
    (doseq [[item-type item] [["command_execution"
                               {:command "bash -lc ls" :aggregated_output ""
                                :exit_code 0 :status "completed"}]
                              ["file_change"
                               {:changes [{:path "a.py" :kind "update"}]
                                :status "completed"}]
                              ["web_search"
                               {:query "clojure" :action {:type "search" :query "clojure"}}]
                              ["collab_tool_call"
                               {:tool "spawn_agent" :sender_thread_id "t0"
                                :receiver_thread_ids [] :prompt nil
                                :agents_states {} :status "completed"}]]]
      (let [parsed (parse-event {:type "item.completed"
                                 :item (assoc item :id "item_9" :type item-type)})]
        (is (true? (:tool-use parsed)) item-type)
        (is (= item-type (:tool-name parsed)))
        (is (= "item_9" (:tool-call-id parsed)))
        (is (= "" (:delta parsed))))))
  (testing "a failed command is still a call the session made"
    (is (= "command_execution"
           (:tool-name (parse-event {:type "item.completed"
                                     :item {:id "item_1" :type "command_execution"
                                            :command "bash -lc 'exit 2'"
                                            :aggregated_output "" :exit_code 2
                                            :status "failed"}})))))
  (testing "item.started is not a second call, and a to-do list is not a call"
    (is (nil? (parse-event (nth hello-run-events 3))))
    (is (nil? (parse-event (nth hello-run-events 10))))
    (is (nil? (parse-event (nth hello-run-events 12))))))

(deftest ^{:stratum 1} turn-completed-keeps-cache-write-tokens-test
  (testing "the prompt-cache write count Codex reports rides on :usage"
    (is (= {:input-tokens 24763 :output-tokens 122
            :cached-input-tokens 24448 :cache-write-input-tokens 0
            :reasoning-output-tokens 64}
           (:usage (parse-event (last hello-run-events)))))))

(deftest ^{:stratum 1} codex-run-reports-native-tool-calls-test
  (testing "a native Codex run reports the local tool calls it ran, apart from its one turn"
    (let [resp (streamed-codex-response hello-run-events 0)]
      (is (:success resp))
      (is (= ["command_execution" "file_change" "command_execution" "context_read"]
             (:tools-called resp)))
      (is (= 4 (:tool-call-count resp)))
      (is (= 1 (:num-turns resp)) "one codex exec turn, four tool calls")
      (is (= (+ 24763 122) (:tokens resp)))
      (is (= "Added hello.py and ran it.\n" (:content resp)))))
  (testing "a run that fails after running commands still reports them"
    (let [events (-> (subvec hello-run-events 0 8)
                     (conj {:type "turn.failed" :error {:message "stream disconnected"}}))
          resp (streamed-codex-response events 1)]
      (is (not (:success resp)))
      (is (= ["command_execution" "file_change" "command_execution"]
             (:tools-called resp)))
      (is (= 3 (:tool-call-count resp))))))
