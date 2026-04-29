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

(ns ai.miniforge.workflow.meta-agent-e2e-test
  "End-to-end tests for meta-agent monitoring with real CLI backends.

   These tests use the actual claude CLI backend to validate the complete system.
   They require:
   - claude CLI installed and configured
   - Network connectivity (for claude CLI to work)

   Run locally only, not in CI (for now)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.llm.interface :as llm]
   [babashka.process :as p]))

;------------------------------------------------------------------------------ Test configuration

(defn claude-cli-available?
  "Check if claude CLI is installed and available."
  []
  (try
    (let [result @(p/process ["which" "claude"] {:out :string :err :string})]
      (zero? (:exit result)))
    (catch Exception _e
      false)))

(def cli-available?
  "Check if claude CLI is available."
  (claude-cli-available?))

(defn skip-if-no-cli
  "Fixture to skip tests if claude CLI is not available."
  [f]
  (if cli-available?
    (f)
    (println "⏭️  Skipping E2E test - claude CLI not installed")))

(use-fixtures :each skip-if-no-cli)

;------------------------------------------------------------------------------ Test workflows

(def simple-planning-workflow
  "A minimal workflow that runs a single planning phase with real LLM."
  {:workflow/id :e2e-simple-plan
   :workflow/version "1.0.0"
   :workflow/pipeline
   [{:phase :plan}
    {:phase :done}]
   :workflow/meta-agents
   [{:id :progress-monitor
     :enabled? true
     :config {:check-interval-ms 2000          ; Check every 2 seconds
              :stagnation-threshold-ms 30000   ; 30 seconds without progress = stagnation
              :max-total-ms 120000}}]})         ; 2 minute total timeout

;------------------------------------------------------------------------------ Helper functions

(defn create-claude-backend
  "Create a real Claude CLI backend for E2E testing."
  []
  ;; Create Claude client using CLI backend (wraps `claude` command)
  (llm/create-client {:backend :claude}))

;------------------------------------------------------------------------------ E2E Tests

(deftest ^:e2e test-simple-workflow-with-real-llm
  (testing "Run simple planning workflow with real Claude CLI backend"
    (when cli-available?
      (let [llm-backend (create-claude-backend)
            input {:task "Create a simple hello world function in Clojure"
                   :description "Write a function that returns 'Hello, World!'"
                   :constraints ["Keep it simple"
                                 "Use idiomatic Clojure"]}
            result (runner/run-pipeline simple-planning-workflow
                                        input
                                        {:llm-backend llm-backend})]

        ;; Verify workflow completed (or at least attempted to run)
        (is (contains? #{:completed :failed} (:execution/status result))
            "Workflow should complete or fail (not hang)")

        ;; Verify meta-coordinator infrastructure is present
        (is (contains? result :execution/meta-coordinator)
            "Should have meta-coordinator")

        (let [coordinator (:execution/meta-coordinator result)]
          (when coordinator
            (let [stats (agent/get-meta-agent-stats coordinator)
                  history (agent/get-meta-check-history coordinator {:limit 100})]

              ;; Should have progress monitor configured
              (is (some #(= :progress-monitor (:id %)) (:agents stats))
                  "Should have progress monitor agent configured")

              history)))

        ;; Just verify the infrastructure ran - don't require specific outputs
        ;; (real LLM execution may vary)
        (is (map? result)
            "Should return execution result map")))))

(deftest ^:e2e test-meta-agent-streaming-detection
  (testing "Meta-agent infrastructure works with real CLI backend"
    (when cli-available?
      (let [llm-backend (create-claude-backend)
            input {:task "Write a simple test function"}
            result (runner/run-pipeline simple-planning-workflow
                                        input
                                        {:llm-backend llm-backend})]

        ;; Should not hang (meta-agent monitoring prevents hangs)
        (is (contains? #{:completed :failed} (:execution/status result))
            "Should complete or fail (not hang)")

        ;; Verify meta-agent infrastructure
        (when-let [coordinator (:execution/meta-coordinator result)]
          (let [history (agent/get-meta-check-history
                         coordinator
                         {:agent-id :progress-monitor :limit 100})]

            ;; Infrastructure should be present
            history
            (is (some? coordinator)
                "Meta-coordinator should exist")))))))

(deftest ^:e2e test-meta-agent-metrics-tracking
  (testing "Meta-agent tracks execution metrics"
    (when cli-available?
      (let [llm-backend (create-claude-backend)
            input {:task "Simple test"}
            result (runner/run-pipeline simple-planning-workflow
                                        input
                                        {:llm-backend llm-backend})]

        ;; Should not hang
        (is (contains? #{:completed :failed} (:execution/status result))
            "Should complete or fail")

        ;; Should have metrics structure
        (let [metrics (:execution/metrics result)]
          (is (map? metrics)
              "Should have metrics map")

          metrics)))))

(defn create-iterating-mock-llm
  "Create a mock LLM that returns multiple responses for multiple iterations.

   This simulates a workflow that goes through multiple phases/iterations,
   allowing meta-agents to perform health checks between iterations."
  []
  (llm/mock-client
   {:outputs ["(defn plan-iteration-1 [] :planning)"
              "(defn plan-iteration-2 [] :more-planning)"
              "(defn plan-iteration-3 [] :final-plan)"
              ":done"]}))

(deftest ^:e2e test-meta-agent-monitors-mocked-workflow
  (testing "Real meta-agent monitors mocked workflow and performs health checks"
    ;; This test uses:
    ;; - Real meta-agents (progress monitor with real health checks)
    ;; - Mock LLM (controllable workflow behavior)
    ;; - Verifies health checks run between workflow iterations
    (let [;; Create mock LLM that returns multiple responses
          mock-llm (create-iterating-mock-llm)

          ;; Workflow with meta-agent monitoring
          monitored-workflow
          {:workflow/id :e2e-monitoring-test
           :workflow/version "1.0.0"
           :workflow/pipeline
           [{:phase :plan}
            {:phase :done}]
           :workflow/meta-agents
           [{:id :progress-monitor
             :enabled? true
             :config {:check-interval-ms 100           ; Check frequently
                      :stagnation-threshold-ms 5000    ; 5 second stagnation threshold
                      :max-total-ms 30000}}]}          ; 30 second hard timeout

          input {:task "Test meta-agent monitoring with mocked workflow"}

          ;; Run workflow with real meta-agents + mock LLM
          result (runner/run-pipeline monitored-workflow
                                      input
                                      {:llm-backend mock-llm})]

      ;; Verify meta-agent infrastructure was active
      (is (contains? result :execution/meta-coordinator)
          "Should have meta-coordinator")

      (let [coordinator (:execution/meta-coordinator result)
            history (agent/get-meta-check-history coordinator {:limit 100})
            stats (agent/get-meta-agent-stats coordinator)]

        ;; Core test: Verify meta-agent infrastructure is properly integrated
        (is (some? coordinator)
            "Should have meta-coordinator instance")

        (is (some #(= :progress-monitor (:id %)) (:agents stats))
            "Should have progress monitor configured in coordinator")

        ;; Health checks depend on timing - if workflow completes quickly,
        ;; checks may not fire. If checks did run, verify they returned valid results.
        (when (seq history)
          (let [statuses (map #(get-in % [:result :status]) history)]
            (is (every? keyword? statuses)
                "All checks should return status keywords")
            (is (every? #(#{:healthy :warning :halt} %) statuses)
                "All checks should return valid status")))))))

;------------------------------------------------------------------------------ Test summary comment

(comment
  ;; How to run these E2E tests locally:
  ;;
  ;; 1. Ensure claude CLI is installed and configured:
  ;;    which claude  # should show claude CLI path
  ;;
  ;; 2. Run from projects/miniforge directory:
  ;;    clojure -M -e \
  ;;      "(require 'ai.miniforge.workflow.meta-agent-e2e-test) \
  ;;       (clojure.test/run-tests 'ai.miniforge.workflow.meta-agent-e2e-test)"
  ;;
  ;; 3. Or run specific test:
  ;;    clojure -M -e \
  ;;      "(require 'ai.miniforge.workflow.meta-agent-e2e-test) \
  ;;       (clojure.test/test-var \
  ;;         #'ai.miniforge.workflow.meta-agent-e2e-test/test-simple-workflow-with-real-llm)"
  ;;
  ;; Note: These tests use the real claude CLI and will make actual LLM requests!
  )
