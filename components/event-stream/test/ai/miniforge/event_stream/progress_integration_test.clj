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

(ns ai.miniforge.event-stream.progress-integration-test
  "Integration tests for end-to-end event sequence coverage.

   Verifies that:
   1. Workflow launch emits required lifecycle events in correct order
   2. Event ordering and visibility in a simulated end-to-end run
   3. Chain event lifecycle ordering
   4. Event-stream subscribers can filter and observe full workflow runs"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Test helpers

(defn simulate-workflow-run!
  "Simulate a complete workflow run by publishing a realistic sequence of
   lifecycle events. Returns the event stream and captured events."
  []
  (let [stream (es/create-event-stream {:sinks []})
        wf-id (random-uuid)
        captured (atom [])]
    (es/subscribe! stream :test-capture
                   (fn [event] (swap! captured conj event)))
    ;; 1. Workflow started
    (es/publish! stream (es/workflow-started stream wf-id {:name "test-feature"}))
    ;; 2. Phase: plan
    (es/publish! stream (es/phase-started stream wf-id :plan))
    (es/publish! stream (es/agent-started stream wf-id :planner))
    (es/publish! stream (es/agent-status stream wf-id :planner :thinking "Analyzing spec"))
    (es/publish! stream (es/tool-invoked stream wf-id :planner :tools/read-file))
    (es/publish! stream (es/tool-completed stream wf-id :planner :tools/read-file))
    (es/publish! stream (es/agent-completed stream wf-id :planner))
    (es/publish! stream (es/phase-completed stream wf-id :plan {:outcome :success :duration-ms 2500}))
    ;; 3. Phase: implement
    (es/publish! stream (es/phase-started stream wf-id :implement))
    (es/publish! stream (es/agent-started stream wf-id :implementer))
    (es/publish! stream (es/tool-invoked stream wf-id :implementer :tools/write-file))
    (es/publish! stream (es/tool-completed stream wf-id :implementer :tools/write-file))
    (es/publish! stream (es/agent-completed stream wf-id :implementer))
    ;; 4. Gate checks
    (es/publish! stream (es/gate-started stream wf-id :lint))
    (es/publish! stream (es/gate-passed stream wf-id :lint 120))
    (es/publish! stream (es/gate-started stream wf-id :test))
    (es/publish! stream (es/gate-failed stream wf-id :test [{:message "1 failing test"}]))
    ;; 5. Milestone
    (es/publish! stream (es/milestone-reached stream wf-id :code-generated "Code generated"))
    (es/publish! stream (es/phase-completed stream wf-id :implement {:outcome :success :duration-ms 8000}))
    ;; 6. Workflow completed
    (es/publish! stream (es/workflow-completed stream wf-id :success 12000))
    {:stream stream :wf-id wf-id :events captured}))

(defn event-type-seq
  "Extract a vector of :event/type from captured events."
  [captured-atom]
  (mapv :event/type @captured-atom))

;------------------------------------------------------------------------------ Layer 1
;; Event ordering and completeness tests

(deftest workflow-lifecycle-event-ordering-test
  (testing "Events are emitted in correct chronological order with monotonic sequence numbers"
    (let [{:keys [events]} (simulate-workflow-run!)
          evts @events
          seq-nums (mapv :event/sequence-number evts)]
      ;; Sequence numbers must be monotonically increasing
      (is (= seq-nums (sort seq-nums))
          "Event sequence numbers must be monotonically increasing")
      ;; All events must have required envelope fields
      (doseq [e evts]
        (is (some? (:event/type e)) "Every event must have :event/type")
        (is (uuid? (:event/id e)) "Every event must have a UUID :event/id")
        (is (inst? (:event/timestamp e)) "Every event must have :event/timestamp")
        (is (string? (:event/version e)) "Every event must have :event/version")
        (is (int? (:event/sequence-number e)) "Every event must have :event/sequence-number")))))

(deftest required-lifecycle-events-present-test
  (testing "All required lifecycle event types are present in a full run"
    (let [{:keys [events]} (simulate-workflow-run!)
          event-types (set (map :event/type @events))]
      ;; Workflow lifecycle
      (is (contains? event-types :workflow/started) "Must emit workflow/started")
      (is (contains? event-types :workflow/completed) "Must emit workflow/completed")
      ;; Phase lifecycle
      (is (contains? event-types :workflow/phase-started) "Must emit phase-started")
      (is (contains? event-types :workflow/phase-completed) "Must emit phase-completed")
      ;; Agent lifecycle
      (is (contains? event-types :agent/started) "Must emit agent/started")
      (is (contains? event-types :agent/completed) "Must emit agent/completed")
      (is (contains? event-types :agent/status) "Must emit agent/status")
      ;; Tool lifecycle
      (is (contains? event-types :tool/invoked) "Must emit tool/invoked")
      (is (contains? event-types :tool/completed) "Must emit tool/completed")
      ;; Gate lifecycle
      (is (contains? event-types :gate/started) "Must emit gate/started")
      (is (contains? event-types :gate/passed) "Must emit gate/passed")
      (is (contains? event-types :gate/failed) "Must emit gate/failed")
      ;; Milestone
      (is (contains? event-types :workflow/milestone-reached) "Must emit milestone"))))

(deftest event-count-test
  (testing "Full workflow run emits expected number of events"
    (let [{:keys [events]} (simulate-workflow-run!)]
      ;; 20 events in simulate-workflow-run!
      (is (= 20 (count @events))
          "Full simulation must emit exactly 20 events"))))

(deftest event-causal-ordering-test
  (testing "Workflow-started is first and workflow-completed is last"
    (let [{:keys [events]} (simulate-workflow-run!)
          evts @events]
      (is (= :workflow/started (:event/type (first evts)))
          "First event must be workflow/started")
      (is (= :workflow/completed (:event/type (last evts)))
          "Last event must be workflow/completed")))

  (testing "Phase-started precedes phase-completed for each phase"
    (let [{:keys [events]} (simulate-workflow-run!)
          evts @events
          phase-events (filter #(#{:workflow/phase-started :workflow/phase-completed}
                                  (:event/type %))
                               evts)
          by-phase (group-by :workflow/phase phase-events)]
      (doseq [[phase pevts] by-phase]
        (let [started-seq (->> pevts
                               (filter #(= :workflow/phase-started (:event/type %)))
                               first
                               :event/sequence-number)
              completed-seq (->> pevts
                                 (filter #(= :workflow/phase-completed (:event/type %)))
                                 first
                                 :event/sequence-number)]
          (when (and started-seq completed-seq)
            (is (< started-seq completed-seq)
                (str "Phase " (name phase) " started must precede completed")))))))

  (testing "Agent-started precedes agent-completed for each agent within a phase"
    (let [{:keys [events]} (simulate-workflow-run!)
          evts @events
          agent-events (filter #(#{:agent/started :agent/completed} (:event/type %)) evts)
          by-agent (group-by :agent/id agent-events)]
      (doseq [[agent-id aevts] by-agent]
        (let [started-seq (->> aevts
                               (filter #(= :agent/started (:event/type %)))
                               first
                               :event/sequence-number)
              completed-seq (->> aevts
                                 (filter #(= :agent/completed (:event/type %)))
                                 first
                                 :event/sequence-number)]
          (when (and started-seq completed-seq)
            (is (< started-seq completed-seq)
                (str "Agent " (name agent-id) " started must precede completed"))))))))

(deftest gate-event-ordering-test
  (testing "Gate-started precedes gate-passed/failed for each gate"
    (let [{:keys [events]} (simulate-workflow-run!)
          evts @events
          gate-events (filter #(#{:gate/started :gate/passed :gate/failed} (:event/type %)) evts)
          by-gate (group-by :gate/id gate-events)]
      (doseq [[gate-id gevts] by-gate]
        (let [started-seq (->> gevts
                               (filter #(= :gate/started (:event/type %)))
                               first
                               :event/sequence-number)
              result-seq (->> gevts
                              (filter #(#{:gate/passed :gate/failed} (:event/type %)))
                              first
                              :event/sequence-number)]
          (when (and started-seq result-seq)
            (is (< started-seq result-seq)
                (str "Gate " (name gate-id) " started must precede result"))))))))

(deftest tool-event-ordering-test
  (testing "Tool-invoked precedes tool-completed for same tool in same agent"
    (let [{:keys [events]} (simulate-workflow-run!)
          evts @events
          tool-events (filter #(#{:tool/invoked :tool/completed} (:event/type %)) evts)]
      ;; Collect pairs by agent and tool
      (doseq [[[agent tool] tevts] (group-by (juxt :agent/id :tool/id) tool-events)]
          (let [invoked-seq (->> tevts
                                 (filter #(= :tool/invoked (:event/type %)))
                                 first
                                 :event/sequence-number)
                completed-seq (->> tevts
                                   (filter #(= :tool/completed (:event/type %)))
                                   first
                                   :event/sequence-number)]
            (when (and invoked-seq completed-seq)
              (is (< invoked-seq completed-seq)
                  (str "Tool " tool " invoked by " agent " must precede completed"))))))))

;------------------------------------------------------------------------------ Layer 2
;; Chain event end-to-end tests

(deftest chain-event-constructors-test
  (testing "Chain events are created with correct types and fields"
    (let [stream (es/create-event-stream {:sinks []})]
      (let [e (es/chain-started stream :deploy-chain 3)]
        (is (= :chain/started (:event/type e)))
        (is (= :deploy-chain (:chain/id e)))
        (is (= 3 (:chain/step-count e))))

      (let [wf-id (random-uuid)
            e (es/chain-step-started stream :deploy-chain :build 0 wf-id)]
        (is (= :chain/step-started (:event/type e)))
        (is (= :deploy-chain (:chain/id e)))
        (is (= :build (:step/id e)))
        (is (= 0 (:step/index e)))
        (is (= wf-id (:step/workflow-id e))))

      (let [e (es/chain-step-completed stream :deploy-chain :build 0)]
        (is (= :chain/step-completed (:event/type e)))
        (is (= :build (:step/id e))))

      (let [e (es/chain-step-failed stream :deploy-chain :build 0 "oops")]
        (is (= :chain/step-failed (:event/type e)))
        (is (= "oops" (:chain/error e))))

      (let [e (es/chain-completed stream :deploy-chain 15000 3)]
        (is (= :chain/completed (:event/type e)))
        (is (= 15000 (:chain/duration-ms e)))
        (is (= 3 (:chain/step-count e))))

      (let [e (es/chain-failed stream :deploy-chain :build "compile error")]
        (is (= :chain/failed (:event/type e)))
        (is (= :build (:chain/failed-step e)))
        (is (= "compile error" (:chain/error e)))))))

(deftest chain-event-sequence-test
  (testing "Chain events follow correct lifecycle ordering"
    (let [stream (es/create-event-stream {:sinks []})
          captured (atom [])]
      (es/subscribe! stream :test
                     (fn [event] (swap! captured conj event)))
      ;; Simulate chain execution
      (es/publish! stream (es/chain-started stream :deploy-chain 2))
      (es/publish! stream (es/chain-step-started stream :deploy-chain :build 0 (random-uuid)))
      (es/publish! stream (es/chain-step-completed stream :deploy-chain :build 0))
      (es/publish! stream (es/chain-step-started stream :deploy-chain :deploy 1 (random-uuid)))
      (es/publish! stream (es/chain-step-completed stream :deploy-chain :deploy 1))
      (es/publish! stream (es/chain-completed stream :deploy-chain 15000 2))
      ;; Verify ordering
      (let [types (mapv :event/type @captured)]
        (is (= [:chain/started
                :chain/step-started
                :chain/step-completed
                :chain/step-started
                :chain/step-completed
                :chain/completed]
               types)
            "Chain events must follow start → steps → completed order")))))

(deftest chain-event-failed-sequence-test
  (testing "Chain failure terminates sequence correctly"
    (let [stream (es/create-event-stream {:sinks []})
          captured (atom [])]
      (es/subscribe! stream :test
                     (fn [event] (swap! captured conj event)))
      (es/publish! stream (es/chain-started stream :deploy-chain 2))
      (es/publish! stream (es/chain-step-started stream :deploy-chain :build 0 (random-uuid)))
      (es/publish! stream (es/chain-step-failed stream :deploy-chain :build 0 "compile error"))
      (es/publish! stream (es/chain-failed stream :deploy-chain :build "compile error"))
      (let [types (mapv :event/type @captured)]
        (is (= [:chain/started
                :chain/step-started
                :chain/step-failed
                :chain/failed]
               types)
            "Failed chain must end at chain/failed")))))

(deftest chain-events-have-envelope-fields-test
  (testing "Chain events have required N3 envelope fields"
    (let [stream (es/create-event-stream {:sinks []})
          e (es/chain-started stream :my-chain 2)]
      (is (uuid? (:event/id e)) "Chain event must have UUID :event/id")
      (is (inst? (:event/timestamp e)) "Chain event must have :event/timestamp")
      (is (string? (:event/version e)) "Chain event must have :event/version")
      (is (int? (:event/sequence-number e)) "Chain event must have :event/sequence-number"))))

;------------------------------------------------------------------------------ Layer 3
;; Multiple workflow isolation test

(deftest multiple-workflow-isolation-test
  (testing "Events from different workflows do not interfere"
    (let [stream (es/create-event-stream {:sinks []})
          wf-1 (random-uuid)
          wf-2 (random-uuid)
          captured (atom [])]
      (es/subscribe! stream :test
                     (fn [event] (swap! captured conj event)))
      ;; Interleave events from two workflows
      (es/publish! stream (es/workflow-started stream wf-1))
      (es/publish! stream (es/workflow-started stream wf-2))
      (es/publish! stream (es/phase-started stream wf-1 :plan))
      (es/publish! stream (es/phase-started stream wf-2 :implement))
      (es/publish! stream (es/workflow-completed stream wf-1 :success 5000))
      (es/publish! stream (es/workflow-completed stream wf-2 :success 8000))
      ;; Filter by workflow
      (let [wf1-events (filter #(= wf-1 (:workflow/id %)) @captured)
            wf2-events (filter #(= wf-2 (:workflow/id %)) @captured)]
        (is (= 3 (count wf1-events)) "WF1 should have 3 events")
        (is (= 3 (count wf2-events)) "WF2 should have 3 events")
        ;; Verify phase association
        (is (= :plan (:workflow/phase (second wf1-events))))
        (is (= :implement (:workflow/phase (second wf2-events))))))))

(deftest subscriber-filter-integration-test
  (testing "Filtered subscriber only sees matching events in full workflow"
    (let [stream (es/create-event-stream {:sinks []})
          wf-id (random-uuid)
          gate-events (atom [])]
      (es/subscribe! stream :gate-only
                     (fn [event] (swap! gate-events conj event))
                     (fn [event] (#{:gate/started :gate/passed :gate/failed}
                                   (:event/type event))))
      ;; Run a full simulation
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/phase-started stream wf-id :plan))
      (es/publish! stream (es/gate-started stream wf-id :lint))
      (es/publish! stream (es/gate-passed stream wf-id :lint 50))
      (es/publish! stream (es/gate-started stream wf-id :test))
      (es/publish! stream (es/gate-failed stream wf-id :test [{:msg "fail"}]))
      (es/publish! stream (es/workflow-completed stream wf-id :success 1000))
      ;; Only gate events should be received
      (is (= 4 (count @gate-events)))
      (is (every? #(#{:gate/started :gate/passed :gate/failed} (:event/type %))
                  @gate-events)))))

;------------------------------------------------------------------------------ Layer 4
;; Edge case and robustness tests

(deftest empty-workflow-run-test
  (testing "Minimal workflow: just started and completed"
    (let [stream (es/create-event-stream {:sinks []})
          wf-id (random-uuid)
          captured (atom [])]
      (es/subscribe! stream :test
                     (fn [event] (swap! captured conj event)))
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/workflow-completed stream wf-id :success 100))
      (is (= 2 (count @captured)))
      (is (= :workflow/started (:event/type (first @captured))))
      (is (= :workflow/completed (:event/type (second @captured)))))))

(deftest workflow-failed-path-test
  (testing "Workflow that fails emits started then failed"
    (let [stream (es/create-event-stream {:sinks []})
          wf-id (random-uuid)
          captured (atom [])]
      (es/subscribe! stream :test
                     (fn [event] (swap! captured conj event)))
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/phase-started stream wf-id :plan))
      (es/publish! stream (es/agent-failed stream wf-id :planner {:message "timeout"}))
      (es/publish! stream (es/workflow-failed stream wf-id {:message "Agent failed"}))
      (let [types (mapv :event/type @captured)]
        (is (= :workflow/started (first types)))
        (is (= :workflow/failed (last types)))))))

(deftest get-events-integration-after-full-run-test
  (testing "get-events returns all events from a full workflow run"
    (let [{:keys [stream events]} (simulate-workflow-run!)
          stored (es/get-events stream)]
      (is (= (count @events) (count stored))
          "Stored event count must match subscribed event count"))))

(deftest get-events-filtered-by-type-integration-test
  (testing "get-events filters by event-type correctly after full run"
    (let [{:keys [stream]} (simulate-workflow-run!)
          gate-events (es/get-events stream {:event-type :gate/started})]
      (is (= 2 (count gate-events))
          "Full run has 2 gate-started events (lint and test)"))))

(deftest get-latest-status-integration-test
  (testing "get-latest-status returns last agent status after full run"
    (let [{:keys [stream wf-id]} (simulate-workflow-run!)
          latest (es/get-latest-status stream wf-id)]
      (is (some? latest) "Should have at least one status event")
      (is (= :agent/status (:event/type latest))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.progress-integration-test)
  :leave-this-here)
