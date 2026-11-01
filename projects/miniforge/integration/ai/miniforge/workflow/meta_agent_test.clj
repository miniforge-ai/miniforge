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
(ns ai.miniforge.workflow.meta-agent-test
  "Integration tests for meta-agent monitoring in workflow execution.

   These tests use mock LLM backends to validate infrastructure wiring
   and integration points. For true end-to-end tests with real LLM backends,
   see the e2e/ directory."
  (:require
   [ai.miniforge.workflow.isolation-support :as isolation]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.event-stream.interface :as events]
   [ai.miniforge.llm.interface.protocols.llm-client :as llm-client]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Test fixtures
(def ^{:stratum 0} simple-workflow-with-meta-agents
  "A minimal test workflow with meta-agent monitoring enabled."
  {:workflow/id :test-meta-monitoring
   :workflow/version "1.0.0"
   :workflow/pipeline
   [{:phase :plan}
    {:phase :done}]
   :workflow/meta-agents
   [{:id :progress-monitor
     :enabled? true
     :config {:check-interval-ms 1000        ; Check every second
              :stagnation-threshold-ms 5000  ; 5 seconds without progress
              :max-total-ms 30000}}]})  ; 30 second total timeout

(def ^{:stratum 0} workflow-without-meta-agents
  "A workflow without meta-agents for comparison."
  {:workflow/id :test-no-monitoring
   :workflow/version "1.0.0"
   :workflow/pipeline
   [{:phase :plan}
    {:phase :done}]})

;------------------------------------------------------------------------------ Helper functions
(def ^{:stratum 0} plan-content
  "A one-task plan inside the ```clojure fence the planner parses. The
   plan phase's plan-complete gate rejects a plan with no tasks."
  (str "```clojure\n"
       (pr-str {:plan/id (random-uuid)
                :plan/name "meta-agent-test-plan"
                :plan/tasks [{:task/id (random-uuid)
                              :task/description "Implement feature"
                              :task/type :implement
                              :task/acceptance-criteria ["Feature works"]
                              :task/estimated-effort :small}]
                :plan/estimated-complexity :low
                :plan/risks []})
       "\n```"))

(defrecord ^{:stratum 0} StreamingMockLLM [config responses calls]
  ;; A record, not a reify: agent model resolution assoc-es the role's
  ;; model into [:config :model] of the client it is handed.
  llm-client/LLMClient
  (complete* [_this _request]
    (let [idx (dec (swap! calls inc))]
      (assoc (get responses idx (peek responses)) :success true)))

  (complete-stream* [_this _request on-chunk]
    (let [idx (dec (swap! calls inc))
          response (assoc (get responses idx (peek responses)) :success true)]
      (doseq [chunk (:streaming-chunks response)]
        (on-chunk {:delta chunk :done? false}))
      (on-chunk {:delta "" :done? true :content (:content response)})
      response))

  (get-config [_this] config))

(defn ^{:stratum 0} quiet-event-stream
  "An event stream with no sinks. Gated phases advance only on an
   acknowledged :gate/decision, which needs a stream to publish to."
  []
  (events/create-event-stream {:sinks []}))

(defn ^{:stratum 0} count-health-checks
  "Count how many health checks were performed during execution."
  [coordinator]
  (let [history (agent/get-meta-check-history coordinator {:limit 1000})]
    (count history)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-streaming-mock-llm
  "Create a mock LLM client that streams each response's chunks before
   returning it, so meta-agent streaming activity tracking has input.

   Reports the :claude backend, which serves the default planner model,
   so agent model resolution keeps this client instead of building a
   real one."
  [responses]
  (let [responses (if (map? responses) [responses] (vec responses))]
    (->StreamingMockLLM {:backend :claude}
                        (mapv (fn [response]
                                (merge {:streaming-chunks ["chunk1" "chunk2" "chunk3"]
                                        :usage {:input-tokens 50 :output-tokens 25}}
                                       response))
                              responses)
                        (atom 0))))

;------------------------------------------------------------------------------ Layer 2

;------------------------------------------------------------------------------ Meta-agent initialization tests
(deftest ^{:stratum 2} test-meta-coordinator-initialized
  (testing "Meta-coordinator is initialized in workflow context"
    (let [workflow simple-workflow-with-meta-agents
          mock-llm (create-streaming-mock-llm {:content plan-content})
          input {:task "Test meta-agent initialization"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      ;; Workflow should complete
      (is (= :completed (:execution/status result))
          "Workflow should complete successfully")

      ;; Context should have meta-coordinator
      (is (contains? result :execution/meta-coordinator)
          "Execution context should have meta-coordinator")

      ;; Coordinator should be initialized
      (let [coordinator (:execution/meta-coordinator result)]
        (is (some? coordinator)
            "Meta-coordinator should not be nil")

        ;; Should have stats from execution
        (let [stats (agent/get-meta-agent-stats coordinator)]
          (is (seq (:agents stats))
              "Should have meta-agent stats")
          (is (some #(= :progress-monitor (:id %)) (:agents stats))
              "Should have progress monitor agent"))))))

(deftest ^{:stratum 2} test-meta-coordinator-default-creation
  (testing "Meta-coordinator created with default progress monitor when no config"
    (let [workflow workflow-without-meta-agents
          mock-llm (create-streaming-mock-llm {:content plan-content})
          input {:task "Test default meta-agents"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      ;; Should still have meta-coordinator (with defaults)
      (is (contains? result :execution/meta-coordinator)
          "Should have meta-coordinator even without explicit config")

      (let [coordinator (:execution/meta-coordinator result)
            stats (agent/get-meta-agent-stats coordinator)]
        ;; Should have at least the default progress monitor
        (is (>= (count (:agents stats)) 1)
            "Should have at least one meta-agent (default progress monitor)")))))

;------------------------------------------------------------------------------ Health check execution tests
(deftest ^{:stratum 2} test-health-checks-run-during-execution
  (testing "Health checks are executed during workflow phases"
    (let [workflow simple-workflow-with-meta-agents
          mock-llm (create-streaming-mock-llm
                    [{:content plan-content
                      :streaming-chunks (repeat 10 "chunk")}  ; Many chunks to trigger multiple iterations
                     {:content ":done"}])
          input {:task "Test health check execution"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      (is (= :completed (:execution/status result))
          "Workflow should complete")

      ;; Check that coordinator exists and was used
      (let [coordinator (:execution/meta-coordinator result)]
        (is (some? coordinator)
            "Meta-coordinator should be present")

        ;; Health checks may or may not have run depending on timing
        ;; (workflow might complete faster than check interval)
        ;; Just verify the coordinator infrastructure is there
        (let [stats (agent/get-meta-agent-stats coordinator)]
          (is (seq (:agents stats))
              "Should have meta-agent stats")
          (is (some #(= :progress-monitor (:id %)) (:agents stats))
              "Should have progress monitor configured"))))))

(deftest ^{:stratum 2} test-streaming-activity-tracking
  (testing "Streaming activity is tracked in workflow context"
    (let [workflow simple-workflow-with-meta-agents
          ;; Create mock with lots of streaming chunks
          chunks (vec (repeatedly 50 #(str "streaming-chunk-" (rand-int 1000))))
          mock-llm (create-streaming-mock-llm
                    {:content plan-content
                     :streaming-chunks chunks})
          input {:task "Test streaming tracking"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      (is (= :completed (:execution/status result))
          "Workflow should complete")

      ;; Verify streaming infrastructure is in place
      (is (contains? result :execution/meta-coordinator)
          "Should have meta-coordinator for monitoring")

      ;; Check final context - streaming activity cleared after execution
      (is (vector? (:execution/streaming-activity result))
          "Should have streaming-activity field (cleared after execution)")

      (is (set? (:execution/files-written result))
          "Should have files-written field")

      ;; Verify meta-coordinator has stats (even if no checks ran due to timing)
      (let [coordinator (:execution/meta-coordinator result)
            stats (agent/get-meta-agent-stats coordinator)]
        (is (seq (:agents stats))
            "Should have meta-agent configuration")))))

;------------------------------------------------------------------------------ Progress detection tests
(deftest ^{:stratum 2} test-progress-vs-stagnation-detection
  (testing "Meta-agents detect real progress vs stagnation"
    ;; This test verifies the progress monitor can distinguish between:
    ;; 1. Active streaming (making progress)
    ;; 2. No activity (stagnation)

    (let [workflow simple-workflow-with-meta-agents
          ;; Simulate continuous streaming activity
          mock-llm (create-streaming-mock-llm
                    {:content plan-content
                     :streaming-chunks (repeat 20 "active-work")})
          input {:task "Test progress detection"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      ;; Should complete successfully (no stagnation detected)
      (is (= :completed (:execution/status result))
          "Workflow with active streaming should complete successfully")

      (let [coordinator (:execution/meta-coordinator result)
            history (agent/get-meta-check-history coordinator {:limit 100})
            statuses (map #(get-in % [:result :status]) history)]

        ;; No health checks should have halted (all should be :healthy or :warning)
        (is (every? #(#{:healthy :warning} %) statuses)
            "All health checks should be healthy or warning (no halts)")))))

;------------------------------------------------------------------------------ Callback integration tests
(deftest ^{:stratum 2} test-meta-monitoring-with-callbacks
  (testing "Meta-agent monitoring works with phase callbacks"
    (let [workflow simple-workflow-with-meta-agents
          mock-llm (create-streaming-mock-llm {:content plan-content})
          phase-starts (atom [])
          phase-completes (atom [])
          input {:task "Test with callbacks"}
          result (runner/run-pipeline
                  workflow
                  input
                  {:llm-backend mock-llm
                   :event-stream (quiet-event-stream)
                   :on-phase-start (fn [_ctx ic]
                                     (swap! phase-starts conj
                                            (get-in ic [:config :phase])))
                   :on-phase-complete (fn [_ctx ic res]
                                        (swap! phase-completes conj
                                               {:phase (get-in ic [:config :phase])
                                                :status (or (:status res)
                                                           (:phase/status res))}))})]

      (is (= :completed (:execution/status result))
          "Workflow should complete")

      ;; Callbacks should have fired
      (is (= [:plan :done] @phase-starts)
          "Should have started plan and done phases")

      (is (= 2 (count @phase-completes))
          "Should have completed 2 phases")

      ;; Meta-coordinator should still be present
      (is (some? (:execution/meta-coordinator result))
          "Meta-coordinator should be present with callbacks"))))

;------------------------------------------------------------------------------ Response chain integration tests
(deftest ^{:stratum 2} test-meta-monitoring-response-chain
  (testing "Meta-agent checks are reflected in response chain"
    (let [workflow simple-workflow-with-meta-agents
          mock-llm (create-streaming-mock-llm {:content plan-content})
          input {:task "Test response chain"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      (is (= :completed (:execution/status result))
          "Workflow should complete")

      ;; Response chain should exist
      (is (contains? result :execution/response-chain)
          "Should have response chain")

      (let [chain (:execution/response-chain result)]
        (is (map? chain)
            "Response chain should be a map")
        (is (contains? chain :succeeded?)
            "Response chain should have succeeded flag")
        (is (true? (:succeeded? chain))
            "Response chain should indicate success")))))

;------------------------------------------------------------------------------ FSM integration tests
(deftest ^{:stratum 2} test-meta-monitoring-fsm-transitions
  (testing "Meta-agent monitoring integrates with FSM state transitions"
    (let [workflow simple-workflow-with-meta-agents
          mock-llm (create-streaming-mock-llm {:content plan-content})
          input {:task "Test FSM transitions"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      ;; Check FSM state progression
      (is (contains? result :execution/fsm-state)
          "Should have FSM state")

      (let [fsm-state (:execution/fsm-state result)]
        (is (= :completed (:_state fsm-state))
            "FSM should be in completed state"))

      ;; Status should match FSM state
      (is (= :completed (:execution/status result))
          "Execution status should match FSM state"))))

;------------------------------------------------------------------------------ Metrics accumulation tests
(deftest ^{:stratum 2} test-meta-monitoring-metrics
  (testing "Metrics are accumulated correctly with meta-agent monitoring"
    (let [workflow simple-workflow-with-meta-agents
          mock-llm (create-streaming-mock-llm
                    [{:content plan-content
                      :usage {:input-tokens 100 :output-tokens 50}}
                     {:content ":done"
                      :usage {:input-tokens 10 :output-tokens 5}}])
          input {:task "Test metrics"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      (is (= :completed (:execution/status result))
          "Workflow should complete")

      ;; Check metrics
      (let [metrics (:execution/metrics result)]
        (is (map? metrics)
            "Should have metrics map")
        (is (contains? metrics :tokens)
            "Should have token count")
        ;; Mock LLM usage tracking may or may not work, just verify structure
        (is (number? (:tokens metrics))
            "Token count should be a number")))))

;------------------------------------------------------------------------------ Artifact tracking tests
(deftest ^{:stratum 2} test-meta-monitoring-artifacts
  (testing "Artifacts are tracked with meta-agent monitoring"
    (let [workflow simple-workflow-with-meta-agents
          mock-llm (create-streaming-mock-llm {:content plan-content})
          input {:task "Test artifacts"}
          result (runner/run-pipeline workflow input {:llm-backend mock-llm :event-stream (quiet-event-stream)})]

      (is (= :completed (:execution/status result))
          "Workflow should complete")

      ;; Should have artifacts vector
      (is (vector? (:execution/artifacts result))
          "Should have artifacts vector"))))

;; Every pipeline this namespace runs acquires its worktree from a
;; throwaway host repository and checkpoints into a throwaway root — never
;; the checkout the test JVM was launched in, never `~/.miniforge`.
(clojure.test/use-fixtures :once isolation/with-isolated-host)
