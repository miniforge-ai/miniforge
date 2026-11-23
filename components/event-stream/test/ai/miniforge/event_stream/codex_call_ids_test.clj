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
(ns ai.miniforge.event-stream.codex-call-ids-test
  "Codex numbers its items item_0, item_1, ... afresh in every `codex exec`,
   so the published call ids are qualified with the invocation (thread) id,
   and a call whose outcome Codex did not report renders as unknown."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.timeline :as timeline]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} completed-call
  "A parsed Codex tool item, as parse-codex-stream-line returns it."
  [tool-name call-id outcome]
  (cond-> {:delta "" :done? false :tool-use true :tool-completed true
           :tool-name tool-name :tool-call-id call-id}
    (some? outcome) (assoc :tool-error? (= :failed outcome))))

(def ^{:stratum 0} turn-completed
  "parse-codex-stream-line's turn.completed (usage left out)."
  {:delta "" :done? true :increment-turns true :invocation-ended? true})

(defn ^{:stratum 0} turn-failed
  [message]
  {:delta (str "\nError: " message) :done? true :invocation-ended? true})

(defn ^{:stratum 0} top-level-error
  "parse-codex-stream-line's top-level error event: done, but the exec
   keeps running."
  [message]
  {:delta (str "\nError: " message) :done? true})

(defn ^{:stratum 0} published-events
  [parsed]
  (let [stream (es/create-event-stream {:sinks []})
        callback (es/create-streaming-callback stream (random-uuid) :implementer)]
    (doseq [p parsed] (callback p))
    (es/get-events stream {})))

(defn ^{:stratum 0} completed-lines
  "The timeline lines of the :tool/call-completed events; each follows
   its started event."
  [events]
  (->> events
       (filter #(#{:agent/tool-call-started :tool/call-completed} (:event/type %)))
       timeline/render-timeline
       str/split-lines
       rest
       (take-nth 2)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} item-ids-are-scoped-to-their-invocation-test
  (testing "two execs that both number a call item_1 publish distinct ids"
    (let [events (published-events
                  [{:delta "" :done? false :heartbeat true :invocation-id "thread-plan"}
                   (completed-call "command_execution" "item_1" :completed)
                   turn-completed
                   {:delta "" :done? false :heartbeat true :invocation-id "thread-impl"}
                   (completed-call "file_change" "item_1" :completed)
                   turn-completed])
          started (filter #(= :agent/tool-call-started (:event/type %)) events)
          completed (filter #(= :tool/call-completed (:event/type %)) events)]
      (is (= ["thread-plan/item_1" "thread-impl/item_1"] (map :tool/call-id started)))
      (is (= (map :tool/call-id started) (map :tool/call-id completed)))
      (let [[plan-line impl-line] (completed-lines events)]
        (is (str/includes? plan-line "command_execution"))
        (is (str/includes? impl-line "file_change")))))
  (testing "a provider that names no invocation keeps its own call ids"
    (let [events (published-events [{:tool-use true :tool-name "Bash" :tool-call-id "toolu_1"}])]
      (is (= ["toolu_1"] (distinct (keep :tool/call-id events))))))
  (testing "ids after the invocation ends are no longer qualified"
    (let [events (published-events
                  [{:delta "" :done? false :heartbeat true :invocation-id "thread-a"}
                   turn-completed
                   {:tool-use true :tool-name "Bash" :tool-call-id "toolu_2"}])]
      (is (= ["toolu_2"] (distinct (keep :tool/call-id events))))))
  (testing "a top-level error does not end the invocation: items Codex
            reconciles before turn.failed keep the thread id"
    (let [events (published-events
                  [{:delta "" :done? false :heartbeat true :invocation-id "thread-a"}
                   (top-level-error "stream disconnected")
                   (completed-call "command_execution" "item_2" nil)
                   (turn-failed "stream disconnected")
                   {:delta "" :done? false :heartbeat true :invocation-id "thread-b"}
                   (top-level-error "stream disconnected")
                   (completed-call "file_change" "item_2" nil)
                   (turn-failed "stream disconnected")])
          started (filter #(= :agent/tool-call-started (:event/type %)) events)]
      (is (= ["thread-a/item_2" "thread-b/item_2"] (map :tool/call-id started)))
      (let [[a-line b-line] (completed-lines events)]
        (is (str/includes? a-line "command_execution"))
        (is (str/includes? b-line "file_change"))))))

(deftest ^{:stratum 1} unreported-outcome-renders-as-unknown-test
  (testing "an item still in progress when its turn ended is not shown as a success"
    (let [[line] (completed-lines (published-events
                                   [(completed-call "command_execution" "item_3" nil)]))]
      (is (str/includes? line "outcome unknown"))
      (is (not (str/includes? line "success")))))
  (testing "reported outcomes still render as before"
    (let [[ok failed] (completed-lines (published-events
                                        [(completed-call "command_execution" "item_4" :completed)
                                         (completed-call "command_execution" "item_5" :failed)]))]
      (is (str/includes? ok "success"))
      (is (str/includes? failed "error")))))
