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

(ns ai.miniforge.logging.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test helpers

(defn get-entries
  "Helper to create a collecting logger, run f with it, and return entries."
  [f]
  (let [[logger entries] (log/collecting-logger {:min-level :trace})]
    (f logger)
    @entries))

;------------------------------------------------------------------------------ Layer 1
;; Logger creation tests

(deftest create-logger-test
  (testing "creates logger with default options"
    (let [logger (log/create-logger)]
      (is (some? logger))
      (is (= {} (log/get-context logger)))))

  (testing "creates logger with initial context"
    (let [ctx {:ctx/workflow-id (random-uuid)}
          logger (log/create-logger {:context ctx})]
      (is (= ctx (log/get-context logger))))))

(deftest collecting-logger-test
  (testing "collecting-logger captures entries"
    (let [[logger entries] (log/collecting-logger)]
      (log/info logger :agent :agent/task-started {})
      (is (= 1 (count @entries)))
      (is (= :info (:log/level (first @entries)))))))

;; Context tests

(deftest with-context-test
  (testing "adds context to logger"
    (let [entries (get-entries
                   (fn [logger]
                     (let [ctx-logger (log/with-context logger {:ctx/workflow-id :wf-1})]
                       (log/info ctx-logger :agent :agent/task-started {}))))]
      (is (= :wf-1 (:ctx/workflow-id (first entries))))))

  (testing "merges multiple contexts"
    (let [entries (get-entries
                   (fn [logger]
                     (let [l1 (log/with-context logger {:ctx/workflow-id :wf-1})
                           l2 (log/with-context l1 {:ctx/task-id :task-1})]
                       (log/info l2 :agent :agent/task-started {}))))
          entry (first entries)]
      (is (= :wf-1 (:ctx/workflow-id entry)))
      (is (= :task-1 (:ctx/task-id entry)))))

  (testing "entry values override context"
    (let [entries (get-entries
                   (fn [logger]
                     (let [ctx-logger (log/with-context logger {:data {:from-ctx true}})]
                       (log/info ctx-logger :agent :agent/task-started
                                 {:data {:from-entry true}}))))]
      (is (= {:from-entry true} (:data (first entries)))))))

;; Level-specific function tests

(deftest log-levels-test
  (testing "trace level"
    (let [entries (get-entries #(log/trace % :agent :agent/memory-updated {}))]
      (is (= :trace (:log/level (first entries))))))

  (testing "debug level"
    (let [entries (get-entries #(log/debug % :loop :inner/iteration-started {}))]
      (is (= :debug (:log/level (first entries))))))

  (testing "info level"
    (let [entries (get-entries #(log/info % :agent :agent/task-started {}))]
      (is (= :info (:log/level (first entries))))))

  (testing "warn level"
    (let [entries (get-entries #(log/warn % :policy :policy/budget-exceeded {}))]
      (is (= :warn (:log/level (first entries))))))

  (testing "error level"
    (let [entries (get-entries #(log/error % :agent :agent/task-failed {}))]
      (is (= :error (:log/level (first entries))))))

  (testing "fatal level"
    (let [entries (get-entries #(log/fatal % :system :system/shutdown {}))]
      (is (= :fatal (:log/level (first entries)))))))

;; Log entry structure tests

(deftest log-entry-structure-test
  (testing "entry has required fields"
    (let [entries (get-entries #(log/info % :agent :agent/task-started {}))
          entry (first entries)]
      (is (uuid? (:log/id entry)))
      (is (inst? (:log/timestamp entry)))
      (is (= :info (:log/level entry)))
      (is (= :agent (:log/category entry)))
      (is (= :agent/task-started (:log/event entry)))))

  (testing "entry includes message when provided"
    (let [entries (get-entries #(log/info % :agent :agent/task-started
                                          {:message "Starting task"}))]
      (is (= "Starting task" (:log/message (first entries))))))

  (testing "entry includes data when provided"
    (let [entries (get-entries #(log/info % :agent :agent/task-started
                                          {:data {:agent-role :implementer}}))]
      (is (= {:agent-role :implementer} (:data (first entries)))))))

;; Min-level filtering tests

(deftest min-level-test
  (testing "filters entries below min-level"
    (let [[logger entries] (log/collecting-logger {:min-level :info})]
      (log/trace logger :agent :agent/memory-updated {})
      (log/debug logger :loop :inner/iteration-started {})
      (log/info logger :agent :agent/task-started {})
      (log/warn logger :policy :policy/budget-exceeded {})
      (is (= 2 (count @entries)))
      (is (every? #{:info :warn} (map :log/level @entries)))))

  (testing "emits all levels when min-level is :trace"
    (let [[logger entries] (log/collecting-logger {:min-level :trace})]
      (log/trace logger :agent :agent/memory-updated {})
      (log/debug logger :loop :inner/iteration-started {})
      (log/info logger :agent :agent/task-started {})
      (is (= 3 (count @entries))))))

;; Timed execution tests

(deftest timed-test
  (testing "timed returns result of function"
    (let [[logger _] (log/collecting-logger)
          result (log/timed logger :info :system :system/health-check
                            #(+ 1 2))]
      (is (= 3 result))))

  (testing "timed logs start and completion"
    (let [[logger entries] (log/collecting-logger)]
      (log/timed logger :info :system :system/health-check (constantly :ok))
      (is (= 2 (count @entries)))
      (is (= "started" (:log/message (first @entries))))
      (is (= "completed" (:log/message (second @entries))))))

  (testing "timed includes duration in completion entry"
    (let [[logger entries] (log/collecting-logger)]
      (log/timed logger :info :system :system/health-check
                 #(Thread/sleep 10))
      (let [completion (second @entries)]
        (is (number? (get-in completion [:data :duration-ms])))
        (is (>= (get-in completion [:data :duration-ms]) 10))))))

;; Generic log function test

(deftest log-function-test
  (testing "log function works with explicit level"
    (let [entries (get-entries
                   #(log/log % :warn :policy :policy/escalation
                             {:message "Escalating to human"
                              :data {:reason "budget exceeded"}}))
          entry (first entries)]
      (is (= :warn (:log/level entry)))
      (is (= :policy (:log/category entry)))
      (is (= :policy/escalation (:log/event entry)))
      (is (= "Escalating to human" (:log/message entry)))
      (is (= {:reason "budget exceeded"} (:data entry))))))

(deftest nil-logger-is-no-op-test
  (testing "every level wrapper is nil-safe"
    (is (nil? (log/trace nil :system :event)))
    (is (nil? (log/debug nil :system :event)))
    (is (nil? (log/info  nil :system :event)))
    (is (nil? (log/warn  nil :system :event)))
    (is (nil? (log/error nil :system :event)))
    (is (nil? (log/fatal nil :system :event)))
    (is (nil? (log/log   nil :info :system :event {}))))

  (testing "with-context passes nil through"
    (is (nil? (log/with-context nil {:ctx/workflow-id "x"}))))

  (testing "timed with nil logger executes f and returns its result"
    (let [calls (atom 0)
          result (log/timed nil :info :system :event
                            (fn [] (swap! calls inc) :done))]
      (is (= :done result))
      (is (= 1 @calls)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.logging.interface-test)

  :leave-this-here)
