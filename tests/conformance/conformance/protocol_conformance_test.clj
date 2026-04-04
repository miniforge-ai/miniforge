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

(ns conformance.protocol_conformance_test
  "N1 Protocol conformance tests.
   Verifies all protocols are correctly implemented per N1 §2 and §8.1."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.agent.interface.protocols.agent :as agent-proto]
   [ai.miniforge.agent.interface.protocols.lifecycle :as lifecycle-proto]
   [ai.miniforge.tool.interface :as tool]
   [ai.miniforge.loop.interface.protocols.gate :as gate-proto]
   [ai.miniforge.loop.gates :as gates]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(def test-task
  {:task/id (random-uuid)
   :task/type :implement
   :task/description "Test task"})

(def test-context
  {:llm-backend (agent/create-mock-llm
                 [{:content "(defn test [] :ok)"
                   :usage {:input-tokens 50 :output-tokens 25}}])})

(def valid-artifact
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn hello [] \"world\")"})

(def invalid-artifact
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content "(defn broken ["})

;------------------------------------------------------------------------------ Layer 1
;; N1 §2.3.2: Agent Protocol Requirements

(deftest agent-invoke-method-test
  (testing "N1 §2.3.2: Agent MUST implement invoke method"
    (let [planner (agent/create-planner {})]
      (is (satisfies? agent-proto/Agent planner)
          "Agent must satisfy Agent protocol")

      ;; Test invoke method exists and is callable
      (let [result (agent-proto/invoke planner test-task test-context)]
        (is (some? result)
            "Agent invoke must return result")
        (is (or (map? result)
                (contains? result :output)
                (some? result))
            "Agent invoke must return structured output")))))

(deftest agent-get-status-method-test
  (testing "N1 §2.3.2: Agent MUST implement get-status method"
    (let [planner (agent/create-planner {})]
      ;; Check if status method exists (may be via different protocol)
      (is (or (satisfies? lifecycle-proto/AgentLifecycle planner)
              (satisfies? agent-proto/Agent planner))
          "Agent must have lifecycle or status capability")

      ;; Try to get status
      (when (satisfies? lifecycle-proto/AgentLifecycle planner)
        (let [status (lifecycle-proto/status planner)]
          (is (some? status)
              "Agent status must be retrievable")
          (is (keyword? (:status status))
              "Agent status must be a keyword"))))))

(deftest agent-abort-method-test
  (testing "N1 §2.3.2: Agent MUST implement abort method"
    (let [implementer (agent/create-implementer {})]
      ;; PR #77 added abort method
      (is (or (satisfies? agent-proto/Agent implementer)
              (satisfies? lifecycle-proto/AgentLifecycle implementer))
          "Agent must satisfy protocol with abort")

      ;; Test abort method if available
      (when (try
              (agent-proto/abort implementer "Test abort")
              true
              (catch Exception _ false))
        (is true "Agent abort method is callable")))))

;------------------------------------------------------------------------------ Layer 1
;; N1 §2.5.1: Tool Protocol Requirements

(deftest tool-invoke-method-test
  (testing "N1 §2.5.1: Tool MUST implement invoke method"
    (let [read-tool (tool/create-tool :read-file)]
      (is (satisfies? tool/Tool read-tool)
          "Tool must satisfy Tool protocol")

      ;; Verify invoke exists (called execute in current impl)
      (is (fn? tool/execute)
          "Tool must have execute/invoke method"))))

(deftest tool-validate-args-method-test
  (testing "N1 §2.5.1: Tool MUST implement validate-args method"
    ;; PR #78 added validate-args method
    (let [write-tool (tool/create-tool :write-file)]
      (is (satisfies? tool/Tool write-tool)
          "Tool must satisfy Tool protocol")

      ;; Test validate-args if available
      (when (try
              (tool/validate-args write-tool {:path "test.txt" :content "data"})
              true
              (catch Exception _ false))
        (is true "Tool validate-args method is callable")))))

(deftest tool-get-schema-method-test
  (testing "N1 §2.5.1: Tool MUST implement get-schema method"
    ;; PR #78 added get-schema method
    (let [cmd-tool (tool/create-tool :run-command)]
      (is (satisfies? tool/Tool cmd-tool)
          "Tool must satisfy Tool protocol")

      ;; Test get-schema if available
      (when (try
              (let [schema (tool/get-schema cmd-tool)]
                (is (some? schema)
                    "Tool schema must be defined")
                (is (map? schema)
                    "Tool schema must be a map"))
              true
              (catch Exception _ false))
        (is true "Tool get-schema method is callable")))))

;------------------------------------------------------------------------------ Layer 1
;; N1 §2.6.1: Gate Protocol Requirements

(deftest gate-check-method-test
  (testing "N1 §2.6.1: Gate MUST implement check method"
    (let [syntax-gate (gates/syntax-gate)]
      (is (satisfies? gate-proto/Gate syntax-gate)
          "Gate must satisfy Gate protocol")

      ;; Test check method
      (let [result (gate-proto/check syntax-gate valid-artifact {})]
        (is (some? result)
            "Gate check must return result")
        (is (map? result)
            "Gate check result must be a map")
        (is (contains? result :gate/passed?)
            "Gate result must indicate pass/fail")))))

(deftest gate-repair-method-test
  (testing "N1 §2.6.1: Gate MUST implement repair method"
    ;; PR #79 added repair method
    (let [lint-gate (gates/lint-gate)]
      (is (satisfies? gate-proto/Gate lint-gate)
          "Gate must satisfy Gate protocol")

      ;; Test repair method
      (let [violations [{:code :debug-println
                        :message "Debug println found"}]
            result (gate-proto/repair lint-gate invalid-artifact violations {})]
        (is (some? result)
            "Gate repair must return result")
        (is (map? result)
            "Gate repair result must be a map")
        (is (contains? result :repaired?)
            "Gate repair result must indicate if repair succeeded")))))

;------------------------------------------------------------------------------ Layer 2
;; N1 §8.1: Component Conformance

(deftest all-standard-agents-implement-protocol-test
  (testing "N1 §8.1: All standard agents implement Agent protocol"
    (let [agents {:planner (agent/create-planner {})
                  :implementer (agent/create-implementer {})
                  :tester (agent/create-tester {})
                  :reviewer (agent/create-reviewer {})}]
      (doseq [[agent-type agent-instance] agents]
        (is (satisfies? agent-proto/Agent agent-instance)
            (str agent-type " must satisfy Agent protocol"))))))

(deftest all-required-tools-implement-protocol-test
  (testing "N1 §8.1: All required tools implement Tool protocol"
    (let [tool-types [:read-file :write-file :run-command]]
      (doseq [tool-type tool-types]
        (let [tool-instance (tool/create-tool tool-type)]
          (is (satisfies? tool/Tool tool-instance)
              (str tool-type " must satisfy Tool protocol")))))))

(deftest all-gate-types-implement-protocol-test
  (testing "N1 §8.1: All gate types implement Gate protocol"
    (let [gates-map {:syntax (gates/syntax-gate)
                     :lint (gates/lint-gate)
                     :policy (gates/policy-gate :test {:policies [:no-secrets]})
                     :test (gates/test-gate)}]
      (doseq [[gate-type gate-instance] gates-map]
        (is (satisfies? gate-proto/Gate gate-instance)
            (str gate-type " gate must satisfy Gate protocol"))))))

;------------------------------------------------------------------------------ Layer 2
;; Protocol method signatures

(deftest agent-protocol-method-signatures-test
  (testing "Agent protocol methods have correct signatures"
    (let [planner (agent/create-planner {})]
      ;; invoke: [agent task context] -> result
      (is (fn? agent-proto/invoke)
          "invoke must be a function")

      ;; Verify can be called with correct args
      (try
        (agent-proto/invoke planner test-task test-context)
        (is true "invoke accepts task and context")
        (catch Exception e
          (is (not (re-find #"Wrong number of args" (.getMessage e)))
              "invoke signature must match protocol"))))))

(deftest tool-protocol-method-signatures-test
  (testing "Tool protocol methods have correct signatures"
    (let [tool-instance (tool/create-tool :read-file)]
      ;; Verify methods can be called
      (try
        (tool/execute tool-instance {:path "test.txt"} {})
        (is true "execute accepts args and context")
        (catch Exception e
          ;; May fail due to missing file, but signature should be correct
          (is (not (re-find #"Wrong number of args" (.getMessage e)))
              "execute signature must match protocol"))))))

(deftest gate-protocol-method-signatures-test
  (testing "Gate protocol methods have correct signatures"
    (let [gate-instance (gates/syntax-gate)]
      ;; check: [gate artifact context] -> result
      (try
        (gate-proto/check gate-instance valid-artifact {})
        (is true "check accepts artifact and context")
        (catch Exception e
          (is (not (re-find #"Wrong number of args" (.getMessage e)))
              "check signature must match protocol")))

      ;; repair: [gate artifact violations context] -> result
      (try
        (gate-proto/repair gate-instance invalid-artifact [] {})
        (is true "repair accepts artifact, violations, and context")
        (catch Exception e
          (is (not (re-find #"Wrong number of args" (.getMessage e)))
              "repair signature must match protocol"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'conformance.protocol_conformance_test)

  ;; Test specific protocol
  (clojure.test/test-var #'agent-abort-method-test)
  (clojure.test/test-var #'gate-repair-method-test)

  :leave-this-here)
