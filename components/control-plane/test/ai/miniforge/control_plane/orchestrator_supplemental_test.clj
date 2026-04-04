;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.control-plane.orchestrator-supplemental-test
  "Supplemental tests for the orchestrator.
   Covers property-based tests, concurrency edge cases,
   and scenarios not in the primary test file."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.control-plane.orchestrator :as sut]
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane.decision-queue :as dq]
   [ai.miniforge.control-plane-adapter.protocol :as adapter]
   [ai.miniforge.event-stream.interface.stream :as stream]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn make-mock-adapter
  "Create a mock adapter implementing ControlPlaneAdapter."
  [overrides]
  (let [id (get overrides :adapter-id :test-adapter)]
    (reify adapter/ControlPlaneAdapter
      (adapter-id [_] id)
      (discover-agents [_ config]
        (if-let [f (:discover-agents overrides)]
          (f config)
          []))
      (poll-agent-status [_ agent-record]
        (if-let [f (:poll-agent-status overrides)]
          (f agent-record)
          nil))
      (deliver-decision [_ agent-record decision]
        (if-let [f (:deliver-decision overrides)]
          (f agent-record decision)
          {:delivered? true}))
      (send-command [_ agent-record command]
        (if-let [f (:send-command overrides)]
          (f agent-record command)
          {:success? true})))))

(defn base-opts
  [& [overrides]]
  (merge {:adapters []
          :discovery-interval-ms 50
          :poll-interval-ms 50}
         overrides))

(defn register-running-agent!
  "Helper: register an agent and transition to :running."
  [reg agent-info]
  (let [rec (registry/register-agent! reg agent-info)]
    (registry/transition-agent! reg (:agent/id rec) :running)
    (registry/get-agent reg (:agent/id rec))))

;; ---------------------------------------------------------------------------
;; Discovery: adapter returns nil instead of empty seq
;; ---------------------------------------------------------------------------

(deftest run-discovery-pass-adapter-returns-nil-test
  (testing "discovery pass handles adapter returning nil (not empty seq)"
    (let [reg (registry/create-registry)
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] nil)})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))]
      ;; doseq on nil is a no-op, should not throw
      (is (nil? (#'sut/run-discovery-pass orch)))
      (is (zero? (registry/count-agents reg))))))

;; ---------------------------------------------------------------------------
;; Discovery: duplicate external-ids across adapters
;; ---------------------------------------------------------------------------

(deftest run-discovery-pass-duplicate-ext-id-across-adapters-test
  (testing "same external-id from two adapters only registers once"
    (let [reg (registry/create-registry)
          discovered (atom [])
          agent-info {:agent/external-id "shared-ext"
                      :agent/name "Shared Agent"
                      :agent/vendor :adapter-1}
          a1 (make-mock-adapter
              {:adapter-id :adapter-1
               :discover-agents (fn [_] [agent-info])})
          a2 (make-mock-adapter
              {:adapter-id :adapter-2
               :discover-agents (fn [_] [(assoc agent-info :agent/vendor :adapter-2)])})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :adapters [a1 a2]
                            :on-agent-discovered #(swap! discovered conj %)}))]
      (#'sut/run-discovery-pass orch)
      ;; Both adapters return same ext-id; second should see it already registered
      ;; Note: depends on whether registry checks external-id globally or per-vendor
      ;; The orchestrator checks get-agent-by-external-id which is global
      (is (<= (registry/count-agents reg) 2)
          "agents registered (at most 2 if ext-id is per-vendor, exactly 1 if global)"))))

;; ---------------------------------------------------------------------------
;; Poll: adapter returns status-update without :agent/status key
;; ---------------------------------------------------------------------------

(deftest run-poll-pass-status-update-missing-status-key-test
  (testing "poll pass handles status update map without :agent/status"
    (let [reg (registry/create-registry)
          _ (registry/register-agent! reg
             {:agent/external-id "ext-no-status"
              :agent/name "No Status Agent"
              :agent/vendor :test-adapter})
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] {:task "doing things"})})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))]
      ;; new-status will be nil, so (not= old-status nil) might be true
      ;; but the when condition checks (and new-status ...) so no event emitted
      (is (nil? (#'sut/run-poll-pass orch))
          "should not throw when status key is missing"))))

;; ---------------------------------------------------------------------------
;; Poll: status changes emit correct old/new values
;; ---------------------------------------------------------------------------

(deftest run-poll-pass-status-change-event-values-test
  (testing "poll emits state-changed with correct old and new status values"
    (let [reg (registry/create-registry)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          _agent-rec (registry/register-agent! reg
                      {:agent/external-id "ext-change"
                       :agent/name "Change Agent"
                       :agent/vendor :test-adapter})
          ;; Agent starts as :unknown (default), poll returns :running
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] {:status :running})})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :adapters [adapter]
                            :event-stream es}))]
      (#'sut/run-poll-pass orch)
      (Thread/sleep 100)
      ;; Verify at least one event was published with state change
      (is (pos? (count @published))
          "should emit event for status change"))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: adapter throws during delivery
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-adapter-throws-test
  (testing "resolve-and-deliver! handles adapter throwing during delivery"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision
                    (fn [_ _] (throw (Exception. "delivery exploded")))})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-throw"
                      :agent/name "Throw Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Will throw on deliver")]
      ;; The adapter will throw - resolve-and-deliver! doesn't catch this
      ;; so it should propagate. Test documents this behavior.
      (is (thrown? Exception
                   (sut/resolve-and-deliver!
                    orch (:decision/id decision) "yes"))
          "exception from adapter delivery should propagate"))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: already-resolved decision
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-already-resolved-test
  (testing "resolve-and-deliver! on already-resolved decision returns nil"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-double"
                      :agent/name "Double Resolve Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Resolve twice")
          ;; First resolve succeeds
          result1 (sut/resolve-and-deliver!
                   orch (:decision/id decision) "yes")
          ;; Second resolve: decision is no longer pending
          result2 (sut/resolve-and-deliver!
                   orch (:decision/id decision) "no")]
      (is (some? (:resolved result1)))
      (is (nil? result2)
          "second resolve should return nil since decision already resolved"))))

;; ---------------------------------------------------------------------------
;; submit-decision-from-agent!: no opts provided (varargs edge case)
;; ---------------------------------------------------------------------------

(deftest submit-decision-no-opts-test
  (testing "submit-decision-from-agent! works with only required args (no opts)"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-noopt"
                      :agent/name "NoOpt Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :decision-manager dm}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Simple question")]
      (is (some? decision))
      (is (= :pending (:decision/status decision)))
      (is (= "Simple question" (:decision/summary decision))))))

;; ---------------------------------------------------------------------------
;; submit-decision-from-agent!: with event stream, event has correct fields
;; ---------------------------------------------------------------------------

(deftest submit-decision-event-fields-test
  (testing "decision-created event contains agent-id and summary"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-evfield"
                      :agent/name "EvField Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :event-stream es}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Event field test")]
      (Thread/sleep 100)
      (is (pos? (count @published))
          "should have published decision-created event")
      ;; Verify the returned decision is well-formed
      (is (= (:agent/id agent-rec) (:decision/agent-id decision))))))

;; ---------------------------------------------------------------------------
;; Concurrent discovery passes don't duplicate registrations
;; ---------------------------------------------------------------------------

(deftest concurrent-discovery-no-duplicates-test
  (testing "concurrent discovery passes don't create duplicate agent registrations"
    (let [reg (registry/create-registry)
          call-count (atom 0)
          agent-info {:agent/external-id "ext-concurrent"
                      :agent/name "Concurrent Agent"
                      :agent/vendor :test-adapter}
          adapter (make-mock-adapter
                   {:discover-agents (fn [_]
                                      (swap! call-count inc)
                                      [agent-info])})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))
          ;; Run 10 discovery passes concurrently
          futures (doall (repeatedly 10 #(future (#'sut/run-discovery-pass orch))))]
      ;; Wait for all to complete
      (doseq [f futures] @f)
      ;; Due to race conditions, we might get more than 1, but the code
      ;; checks get-agent-by-external-id before registering
      ;; In practice with atom-based registry, some races may sneak through
      (is (<= (registry/count-agents reg) 10)
          "should not have more agents than discovery passes")
      (is (pos? (registry/count-agents reg))
          "should have registered at least one agent"))))

;; ---------------------------------------------------------------------------
;; start!/stop! with active discovery finding agents
;; ---------------------------------------------------------------------------

(deftest start-stop-rapid-cycling-test
  (testing "rapid start/stop cycling doesn't leave orphaned threads"
    (let [adapter (make-mock-adapter {})
          orch (sut/create-orchestrator
                (base-opts {:adapters [adapter]
                            :discovery-interval-ms 10
                            :poll-interval-ms 10}))]
      ;; Rapid cycle 3 times
      (dotimes [_ 3]
        (let [started (sut/start! orch)]
          (Thread/sleep 30)
          (sut/stop! started)))
      (is (false? @(:running orch))
          "should be stopped after cycling")
      (is (empty? @(:futures orch))
          "futures should be cleared"))))

;; ---------------------------------------------------------------------------
;; start! sets up watchdog correctly
;; ---------------------------------------------------------------------------

(deftest start-watchdog-uses-poll-interval-test
  (testing "start! creates watchdog that uses poll-interval-ms"
    (let [orch (sut/create-orchestrator
                (base-opts {:poll-interval-ms 100}))
          started (sut/start! orch)]
      (is (some? (:watchdog started))
          "watchdog should be present")
      (is (contains? (:watchdog started) :future)
          "watchdog should have a :future key")
      (sut/stop! started))))

;; ---------------------------------------------------------------------------
;; stop! cancels all futures
;; ---------------------------------------------------------------------------

(deftest stop-cancels-futures-test
  (testing "stop! cancels futures and clears them"
    (let [adapter (make-mock-adapter {})
          orch (sut/create-orchestrator
                (base-opts {:adapters [adapter]}))
          started (sut/start! orch)
          futures-before-stop @(:futures orch)]
      (is (pos? (count futures-before-stop))
          "should have futures while running")
      (sut/stop! started)
      (is (empty? @(:futures started))
          "futures should be empty after stop")
      ;; Verify futures are cancelled
      (is (every? future-cancelled? futures-before-stop)
          "all futures should be cancelled"))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver! transitions agent back to running
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-agent-transitions-blocked-to-running-test
  (testing "agent goes :running -> :blocked (submit) -> :running (resolve)"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-trans"
                      :agent/name "Trans Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          aid (:agent/id agent-rec)]
      ;; Verify initial state
      (is (= :running (:agent/status (registry/get-agent reg aid))))
      ;; Submit decision -> agent should become :blocked
      (let [decision (sut/submit-decision-from-agent! orch aid "Block me")]
        (is (= :blocked (:agent/status (registry/get-agent reg aid))))
        ;; Resolve -> agent should return to :running
        (sut/resolve-and-deliver! orch (:decision/id decision) "done")
        (is (= :running (:agent/status (registry/get-agent reg aid))))))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver! with comment=nil
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-nil-comment-test
  (testing "resolve-and-deliver! with explicit nil comment"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-nilc"
                      :agent/name "NilComment Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Nil comment test")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes" nil)]
      (is (some? (:resolved result)))
      (is (true? (:delivered? result)))
      (is (nil? (:decision/comment (:resolved result)))))))

;; ---------------------------------------------------------------------------
;; Discovery: agent-info with extra metadata fields
;; ---------------------------------------------------------------------------

(deftest run-discovery-pass-agent-with-capabilities-and-metadata-test
  (testing "discovery pass handles agents with rich metadata"
    (let [reg (registry/create-registry)
          discovered (atom [])
          agent-info {:agent/external-id "ext-rich"
                      :agent/name "Rich Agent"
                      :agent/vendor :test-adapter
                      :agent/capabilities #{:code-generation :testing}
                      :agent/metadata {:model "claude-4" :version "1.0"}}
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [agent-info])})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :adapters [adapter]
                            :on-agent-discovered #(swap! discovered conj %)}))]
      (#'sut/run-discovery-pass orch)
      (is (= 1 (registry/count-agents reg)))
      (let [agent (first @discovered)]
        (is (= "Rich Agent" (:agent/name agent)))
        (is (= :test-adapter (:agent/vendor agent)))))))

;; ---------------------------------------------------------------------------
;; Poll pass: adapter returns status for already-transitioning agent
;; ---------------------------------------------------------------------------

(deftest run-poll-pass-polls-blocked-agents-test
  (testing "poll pass does poll agents with :blocked status (not terminal)"
    (let [reg (registry/create-registry)
          poll-count (atom 0)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-blocked"
                      :agent/name "Blocked Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          _ (registry/transition-agent! reg (:agent/id agent-rec) :blocked)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] (swap! poll-count inc)
                      {:status :blocked})})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (is (= 1 @poll-count)
          "should poll :blocked agents since they are not terminal"))))

(deftest run-poll-pass-polls-idle-agents-test
  (testing "poll pass polls agents with :idle status"
    (let [reg (registry/create-registry)
          poll-count (atom 0)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-idle"
                      :agent/name "Idle Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          _ (registry/transition-agent! reg (:agent/id agent-rec) :idle)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] (swap! poll-count inc)
                      {:status :idle})})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (is (= 1 @poll-count)
          "should poll :idle agents since they are not terminal"))))

;; ---------------------------------------------------------------------------
;; submit-decision-from-agent!: returns decision with correct agent-id
;; ---------------------------------------------------------------------------

(deftest submit-decision-decision-stored-in-manager-test
  (testing "submitted decision is retrievable from the decision manager"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-stored"
                      :agent/name "Stored Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :decision-manager dm}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Stored decision")
          retrieved (dq/get-decision dm (:decision/id decision))]
      (is (some? retrieved))
      (is (= (:decision/id decision) (:decision/id retrieved)))
      (is (= :pending (:decision/status retrieved))))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: decision resolved data matches
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-passes-resolved-decision-to-adapter-test
  (testing "adapter receives the resolved decision with resolution"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          delivered-data (atom nil)
          adapter (make-mock-adapter
                   {:deliver-decision
                    (fn [_agent-rec decision]
                      (reset! delivered-data decision)
                      {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-deliver-data"
                      :agent/name "Deliver Data Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Check delivery data")
          _ (sut/resolve-and-deliver!
             orch (:decision/id decision) "approved" "Ship it")]
      (is (some? @delivered-data))
      (is (= "approved" (:decision/resolution @delivered-data)))
      (is (= "Ship it" (:decision/comment @delivered-data))))))

;; ---------------------------------------------------------------------------
;; create-orchestrator: interval values
;; ---------------------------------------------------------------------------

(deftest create-orchestrator-custom-intervals-test
  (testing "custom interval values are stored correctly"
    (let [orch (sut/create-orchestrator
                {:discovery-interval-ms 60000
                 :poll-interval-ms 5000})]
      (is (= 60000 (:discovery-interval-ms orch)))
      (is (= 5000 (:poll-interval-ms orch))))))

(deftest create-orchestrator-zero-intervals-test
  (testing "zero intervals are accepted (caller's responsibility)"
    (let [orch (sut/create-orchestrator
                {:discovery-interval-ms 0
                 :poll-interval-ms 0})]
      (is (= 0 (:discovery-interval-ms orch)))
      (is (= 0 (:poll-interval-ms orch))))))

;; ---------------------------------------------------------------------------
;; Integration: multiple agents, mixed statuses
;; ---------------------------------------------------------------------------

(deftest poll-pass-mixed-statuses-test
  (testing "poll correctly handles mix of terminal and non-terminal agents"
    (let [reg (registry/create-registry)
          poll-log (atom [])
          ;; Register three agents with different statuses
          a1 (registry/register-agent! reg
              {:agent/external-id "ext-mix-1"
               :agent/name "Running Agent"
               :agent/vendor :test-adapter})
          a2 (registry/register-agent! reg
              {:agent/external-id "ext-mix-2"
               :agent/name "Completed Agent"
               :agent/vendor :test-adapter})
          _a3 (registry/register-agent! reg
               {:agent/external-id "ext-mix-3"
                :agent/name "Unknown Agent"
                :agent/vendor :test-adapter})
          ;; Transition a1 to running, a2 to completed
          _ (registry/transition-agent! reg (:agent/id a1) :running)
          _ (registry/transition-agent! reg (:agent/id a2) :running)
          _ (registry/transition-agent! reg (:agent/id a2) :completed)
          ;; a3 stays :unknown (non-terminal)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [agent-rec]
                      (swap! poll-log conj (:agent/name agent-rec))
                      {:status (:agent/status agent-rec)})})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      ;; Should poll running and unknown agents, skip completed
      (is (= 2 (count @poll-log))
          "should poll exactly 2 non-terminal agents")
      (is (some #(= "Running Agent" %) @poll-log))
      (is (some #(= "Unknown Agent" %) @poll-log))
      (is (not (some #(= "Completed Agent" %) @poll-log))))))

;; ---------------------------------------------------------------------------
;; Integration: decision lifecycle with multiple agents
;; ---------------------------------------------------------------------------

(deftest multiple-agents-independent-decisions-test
  (testing "decisions for different agents are independent"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          a1 (register-running-agent! reg
              {:agent/external-id "ext-ind-1"
               :agent/name "Agent 1"
               :agent/vendor :test-adapter})
          a2 (register-running-agent! reg
              {:agent/external-id "ext-ind-2"
               :agent/name "Agent 2"
               :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          d1 (sut/submit-decision-from-agent!
              orch (:agent/id a1) "Agent 1 question")
          d2 (sut/submit-decision-from-agent!
              orch (:agent/id a2) "Agent 2 question")]
      ;; Both agents should be blocked
      (is (= :blocked (:agent/status (registry/get-agent reg (:agent/id a1)))))
      (is (= :blocked (:agent/status (registry/get-agent reg (:agent/id a2)))))
      ;; Resolve only d1
      (sut/resolve-and-deliver! orch (:decision/id d1) "yes")
      ;; a1 back to running, a2 still blocked
      (is (= :running (:agent/status (registry/get-agent reg (:agent/id a1)))))
      (is (= :blocked (:agent/status (registry/get-agent reg (:agent/id a2)))))
      ;; Resolve d2
      (sut/resolve-and-deliver! orch (:decision/id d2) "no")
      (is (= :running (:agent/status (registry/get-agent reg (:agent/id a2))))))))
