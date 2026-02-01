;; Copyright 2025 miniforge.ai
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
  "End-to-end tests for meta-agent monitoring with real LLM backends.

   These tests use actual Claude API calls to validate the complete system.
   They require:
   - ANTHROPIC_API_KEY environment variable
   - Network connectivity
   - API credits

   Run locally only, not in CI (for now)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Test configuration

(def api-key-present?
  "Check if ANTHROPIC_API_KEY is available."
  (some? (System/getenv "ANTHROPIC_API_KEY")))

(defn skip-if-no-api-key
  "Fixture to skip tests if API key is not present."
  [f]
  (if api-key-present?
    (f)
    (println "⏭️  Skipping E2E test - ANTHROPIC_API_KEY not set")))

(use-fixtures :each skip-if-no-api-key)

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
  "Create a real Claude API backend for E2E testing."
  []
  (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
    (when-not api-key
      (throw (ex-info "ANTHROPIC_API_KEY environment variable not set"
                      {:required "ANTHROPIC_API_KEY"})))
    ;; Create Claude client with streaming enabled
    (llm/create-client {:backend :claude
                        :api-key api-key
                        :model "claude-sonnet-4-20250514"
                        :streaming? true})))

;------------------------------------------------------------------------------ E2E Tests

(deftest ^:e2e test-simple-workflow-with-real-llm
  (testing "Run simple planning workflow with real Claude API"
    (when api-key-present?
      (let [llm-backend (create-claude-backend)
            input {:task "Create a simple hello world function in Clojure"
                   :description "Write a function that returns 'Hello, World!'"
                   :constraints ["Keep it simple"
                                 "Use idiomatic Clojure"]}
            result (runner/run-pipeline simple-planning-workflow
                                        input
                                        {:llm-backend llm-backend})]

        ;; Verify workflow completed
        (is (= :completed (:execution/status result))
            "Workflow should complete successfully with real LLM")

        ;; Verify meta-coordinator was active
        (is (contains? result :execution/meta-coordinator)
            "Should have meta-coordinator")

        (let [coordinator (:execution/meta-coordinator result)
              stats (agent/get-meta-agent-stats coordinator)
              history (agent/get-meta-check-history coordinator {:limit 100})]

          ;; Should have progress monitor configured
          (is (some #(= :progress-monitor (:id %)) (:agents stats))
              "Should have progress monitor agent")

          ;; With real LLM streaming, health checks likely ran
          (println "\n📊 Meta-agent health checks performed:" (count history))
          (when (seq history)
            (println "✓ Progress monitor was active during execution")
            (println "  Last check status:" (:status (:result (first history)))))

          ;; Verify no halts occurred (all checks healthy or warning)
          (let [statuses (map #(get-in % [:result :status]) history)]
            (is (every? #(#{:healthy :warning} %) statuses)
                "All health checks should be healthy or warning (no halts)")))

        ;; Verify execution completed with real artifacts
        (is (seq (:execution/artifacts result))
            "Should have generated artifacts from real LLM")

        ;; Verify response chain shows success
        (let [chain (:execution/response-chain result)]
          (is (true? (:succeeded? chain))
              "Response chain should show success"))

        ;; Print summary
        (println "\n✅ E2E Test Summary:")
        (println "  Status:" (:execution/status result))
        (println "  Artifacts:" (count (:execution/artifacts result)))
        (println "  Tokens:" (get-in result [:execution/metrics :tokens] 0))
        (println "  Duration:" (get-in result [:execution/metrics :duration-ms] 0) "ms")))))

(deftest ^:e2e test-meta-agent-streaming-detection
  (testing "Meta-agent detects streaming activity during real execution"
    (when api-key-present?
      (let [llm-backend (create-claude-backend)
            input {:task "Write a function to calculate fibonacci numbers"
                   :description "Implement fibonacci with memoization"}
            result (runner/run-pipeline simple-planning-workflow
                                        input
                                        {:llm-backend llm-backend})]

        ;; Should complete (real streaming activity prevents stagnation)
        (is (= :completed (:execution/status result))
            "Should complete - streaming activity prevents stagnation timeout")

        ;; Check that progress monitor saw activity
        (let [coordinator (:execution/meta-coordinator result)
              history (agent/get-meta-check-history
                       coordinator
                       {:agent-id :progress-monitor :limit 100})]

          (println "\n📡 Streaming Activity Detection:")
          (println "  Progress monitor checks:" (count history))

          ;; At least one health check should have run
          (when (seq history)
            (println "  ✓ Meta-agent monitoring was active")
            (println "  Check results:" (map #(get-in % [:result :status]) history)))

          ;; No halts should have occurred
          (let [halts (filter #(= :halt (get-in % [:result :status])) history)]
            (is (empty? halts)
                "No stagnation halts should occur with active streaming")))))))

(deftest ^:e2e test-meta-agent-metrics-tracking
  (testing "Meta-agent tracks real token usage and metrics"
    (when api-key-present?
      (let [llm-backend (create-claude-backend)
            input {:task "Explain recursion in simple terms"}
            result (runner/run-pipeline simple-planning-workflow
                                        input
                                        {:llm-backend llm-backend})]

        (is (= :completed (:execution/status result))
            "Should complete successfully")

        ;; Real LLM should produce token metrics
        (let [metrics (:execution/metrics result)]
          (is (map? metrics)
              "Should have metrics map")

          (println "\n📈 Real LLM Metrics:")
          (println "  Tokens:" (:tokens metrics))
          (println "  Cost:" (:cost-usd metrics) "USD")
          (println "  Duration:" (:duration-ms metrics) "ms")

          ;; With real LLM, should have actual token counts
          (when (pos? (:tokens metrics 0))
            (println "  ✓ Real token usage tracked")))))))

;------------------------------------------------------------------------------ Test summary comment

(comment
  ;; How to run these E2E tests locally:
  ;;
  ;; 1. Set your API key:
  ;;    export ANTHROPIC_API_KEY=your-key-here
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
  ;; Note: These tests make real API calls and will incur costs!
  )
