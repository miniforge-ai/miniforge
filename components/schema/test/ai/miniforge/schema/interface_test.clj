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

(ns ai.miniforge.schema.interface-test
  (:require [clojure.test :as test :refer [deftest testing is]]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def valid-agent
  {:agent/id (random-uuid)
   :agent/role :implementer
   :agent/capabilities #{:code :test}
   :agent/config {:model "claude-sonnet-4"
                  :max-tokens 8000}})

(def valid-task
  {:task/id (random-uuid)
   :task/type :implement
   :task/status :pending
   :task/constraints {:budget {:tokens 50000}}})

(def valid-artifact
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/version "1.0.0"
   :artifact/content "(defn hello [] \"world\")"})

(def valid-workflow
  {:workflow/id (random-uuid)
   :workflow/name "feature-auth"
   :workflow/status :running
   :workflow/phase :implement
   :workflow/priority 5})

(def valid-log-entry
  {:log/id (random-uuid)
   :log/timestamp (java.util.Date.)
   :log/level :info
   :log/category :agent
   :log/event :agent/task-started
   :ctx/workflow-id (random-uuid)
   :data {:agent-role :implementer}})

(def valid-scenario
  {:scenario/id (random-uuid)
   :scenario/name "happy-path"
   :scenario/tags #{:golden-path}
   :scenario/created-at (java.util.Date.)
   :scenario/status :running})

;------------------------------------------------------------------------------ Layer 1
;; Agent tests

(deftest valid-agent?-test
  (testing "valid agent returns true"
    (is (true? (schema/valid-agent? valid-agent))))

  (testing "minimal valid agent"
    (is (true? (schema/valid-agent?
                {:agent/id (random-uuid)
                 :agent/role :planner}))))

  (testing "invalid agent role returns false"
    (is (false? (schema/valid-agent?
                 {:agent/id (random-uuid)
                  :agent/role :invalid-role}))))

  (testing "invalid agent id returns false"
    (is (false? (schema/valid-agent?
                 {:agent/id "not-a-uuid"
                  :agent/role :implementer})))))

;; Task tests

(deftest valid-task?-test
  (testing "valid task returns true"
    (is (true? (schema/valid-task? valid-task))))

  (testing "minimal valid task"
    (is (true? (schema/valid-task?
                {:task/id (random-uuid)
                 :task/type :plan
                 :task/status :pending}))))

  (testing "invalid task type returns false"
    (is (false? (schema/valid-task?
                 {:task/id (random-uuid)
                  :task/type :invalid
                  :task/status :pending}))))

  (testing "invalid task status returns false"
    (is (false? (schema/valid-task?
                 {:task/id (random-uuid)
                  :task/type :implement
                  :task/status :invalid})))))

;; Artifact tests

(deftest valid-artifact?-test
  (testing "valid artifact returns true"
    (is (true? (schema/valid-artifact? valid-artifact))))

  (testing "minimal valid artifact"
    (is (true? (schema/valid-artifact?
                {:artifact/id (random-uuid)
                 :artifact/type :spec
                 :artifact/version "0.1.0"}))))

  (testing "invalid artifact type returns false"
    (is (false? (schema/valid-artifact?
                 {:artifact/id (random-uuid)
                  :artifact/type :invalid
                  :artifact/version "1.0.0"})))))

;; Workflow tests

(deftest valid-workflow?-test
  (testing "valid workflow returns true"
    (is (true? (schema/valid-workflow? valid-workflow))))

  (testing "minimal valid workflow"
    (is (true? (schema/valid-workflow?
                {:workflow/id (random-uuid)
                 :workflow/status :pending}))))

  (testing "invalid workflow status returns false"
    (is (false? (schema/valid-workflow?
                 {:workflow/id (random-uuid)
                  :workflow/status :invalid})))))

;; LogEntry tests

(deftest valid-log-entry?-test
  (testing "valid log entry returns true"
    (is (true? (schema/valid-log-entry? valid-log-entry))))

  (testing "minimal valid log entry"
    (is (true? (schema/valid-log-entry?
                {:log/id (random-uuid)
                 :log/timestamp (java.util.Date.)
                 :log/level :debug
                 :log/category :system
                 :log/event :system/startup}))))

  (testing "invalid log level returns false"
    (is (false? (schema/valid-log-entry?
                 {:log/id (random-uuid)
                  :log/timestamp (java.util.Date.)
                  :log/level :invalid
                  :log/category :agent
                  :log/event :agent/task-started})))))

;; Scenario tests

(deftest valid-scenario?-test
  (testing "valid scenario returns true"
    (is (true? (schema/valid-scenario? valid-scenario))))

  (testing "invalid scenario status returns false"
    (is (false? (schema/valid-scenario?
                 {:scenario/id (random-uuid)
                  :scenario/name "test"
                  :scenario/created-at (java.util.Date.)
                  :scenario/status :invalid})))))

;; General validation tests

(deftest explain-test
  (testing "valid data returns nil"
    (is (nil? (schema/explain schema/Agent valid-agent))))

  (testing "invalid data returns error map"
    (let [errors (schema/explain schema/Agent {:agent/id "not-uuid"
                                                :agent/role :invalid})]
      (is (map? errors))
      (is (contains? errors :agent/id))
      (is (contains? errors :agent/role)))))

(deftest validate-test
  (testing "valid data returns the data"
    (is (= valid-agent (schema/validate schema/Agent valid-agent))))

  (testing "invalid data throws ex-info"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Schema validation failed"
                          (schema/validate schema/Agent {:agent/id "bad"})))))

;; Enum access tests

(deftest enum-values-test
  (testing "agent-roles contains expected values"
    (is (= 10 (count schema/agent-roles)))
    (is (some #{:planner} schema/agent-roles))
    (is (some #{:implementer} schema/agent-roles)))

  (testing "task-types contains expected values"
    (is (some #{:implement} schema/task-types))
    (is (some #{:test} schema/task-types)))

  (testing "artifact-types contains expected values"
    (is (some #{:code} schema/artifact-types))
    (is (some #{:spec} schema/artifact-types)))

  (testing "log-levels contains expected values"
    (is (= [:trace :debug :info :warn :error :fatal] schema/log-levels)))

  (testing "all-events contains events from all categories"
    (is (some #{:agent/task-started} schema/all-events))
    (is (some #{:system/startup} schema/all-events))
    (is (some #{:policy/gate-evaluated} schema/all-events))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (test/run-tests 'ai.miniforge.schema.interface-test)

  :leave-this-here)
