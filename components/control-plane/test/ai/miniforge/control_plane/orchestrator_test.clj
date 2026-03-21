;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.control-plane.orchestrator-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.control-plane.orchestrator :as sut]
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane.decision-queue :as dq]
   [ai.miniforge.control-plane.heartbeat :as heartbeat]
   [ai.miniforge.control-plane-adapter.protocol :as adapter]
   [ai.miniforge.event-stream.interface.stream :as stream]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn make-mock-adapter
  "Create a mock adapter implementing ControlPlaneAdapter.
   `overrides` is a map of {:discover-agents fn, :poll-agent-status fn,
                             :deliver-decision fn, :send-command fn, :adapter-id kw}."
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

(defn make-test-event-stream
  "Create a minimal event stream that captures published events."
  []
  (let [events-atom (atom [])]
    {:stream (stream/create-event-stream)
     :published events-atom}))

(def test-workflow-id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001"))

(defn base-orchestrator-opts
  "Minimal valid opts for creating an orchestrator."
  [& [overrides]]
  (merge {:adapters []
          :workflow-id test-workflow-id
          :discovery-interval-ms 50
          :poll-interval-ms 50}
         overrides))

;; ---------------------------------------------------------------------------
;; Layer 0: create-orchestrator
;; ---------------------------------------------------------------------------

(deftest create-orchestrator-defaults-test
  (testing "creates orchestrator with default values when minimal opts given"
    (let [orch (sut/create-orchestrator {})]
      (is (some? (:registry orch))
          "should create a default registry")
      (is (some? (:decision-manager orch))
          "should create a default decision manager")
      (is (vector? (:adapters orch))
          "adapters should be a vector")
      (is (empty? (:adapters orch))
          "adapters should default to empty")
      (is (nil? (:event-stream orch))
          "event-stream defaults to nil when not provided")
      (is (= sut/default-discovery-interval-ms (:discovery-interval-ms orch))
          "should use default discovery interval")
      (is (= sut/default-poll-interval-ms (:poll-interval-ms orch))
          "should use default poll interval")
      (is (fn? (:on-agent-discovered orch))
          "should have a no-op on-agent-discovered callback")
      (is (fn? (:on-agent-unreachable orch))
          "should have a no-op on-agent-unreachable callback")
      (is (fn? (:on-decision-created orch))
          "should have a no-op on-decision-created callback")
      (is (false? @(:running orch))
          "should not be running initially")
      (is (empty? @(:futures orch))
          "should have no futures initially"))))

(deftest create-orchestrator-custom-opts-test
  (testing "respects all provided options"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          es (stream/create-event-stream)
          adapter (make-mock-adapter {})
          discovered-atom (atom nil)
          unreachable-atom (atom nil)
          decision-atom (atom nil)
          wf-id (random-uuid)
          orch (sut/create-orchestrator
                {:registry reg
                 :decision-manager dm
                 :adapters [adapter]
                 :event-stream es
                 :workflow-id wf-id
                 :discovery-interval-ms 5000
                 :poll-interval-ms 2000
                 :on-agent-discovered #(reset! discovered-atom %)
                 :on-agent-unreachable #(reset! unreachable-atom %)
                 :on-decision-created #(reset! decision-atom %)})]
      (is (identical? reg (:registry orch))
          "should use provided registry")
      (is (identical? dm (:decision-manager orch))
          "should use provided decision manager")
      (is (= [adapter] (:adapters orch))
          "should use provided adapters")
      (is (identical? es (:event-stream orch))
          "should use provided event stream")
      (is (= wf-id (:workflow-id orch))
          "should use provided workflow-id")
      (is (= 5000 (:discovery-interval-ms orch)))
      (is (= 2000 (:poll-interval-ms orch)))
      ;; Verify callbacks are the ones we provided
      ((:on-agent-discovered orch) :test-agent)
      (is (= :test-agent @discovered-atom)))))

(deftest create-orchestrator-multiple-adapters-test
  (testing "supports multiple adapters as a vector"
    (let [a1 (make-mock-adapter {:adapter-id :adapter-1})
          a2 (make-mock-adapter {:adapter-id :adapter-2})
          orch (sut/create-orchestrator {:adapters [a1 a2]})]
      (is (= 2 (count (:adapters orch))))
      (is (= :adapter-1 (adapter/adapter-id (first (:adapters orch)))))
      (is (= :adapter-2 (adapter/adapter-id (second (:adapters orch))))))))

(deftest create-orchestrator-default-callbacks-are-safe-test
  (testing "default callbacks can be called without error"
    (let [orch (sut/create-orchestrator {})]
      (is (nil? ((:on-agent-discovered orch) {:agent/id (random-uuid)})))
      (is (nil? ((:on-agent-unreachable orch) (random-uuid))))
      (is (nil? ((:on-decision-created orch) {:decision/id (random-uuid)}))))))

(deftest create-orchestrator-running-and-futures-are-atoms-test
  (testing "running and futures are independent atoms per orchestrator"
    (let [orch1 (sut/create-orchestrator {})
          orch2 (sut/create-orchestrator {})]
      (reset! (:running orch1) true)
      (is (true? @(:running orch1)))
      (is (false? @(:running orch2))
          "orch2 running should be independent of orch1"))))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(deftest default-constants-test
  (testing "default interval constants are positive integers"
    (is (pos-int? sut/default-discovery-interval-ms))
    (is (pos-int? sut/default-poll-interval-ms))
    (is (= 30000 sut/default-discovery-interval-ms))
    (is (= 10000 sut/default-poll-interval-ms))))

;; ---------------------------------------------------------------------------
;; Private emit! helper
;; ---------------------------------------------------------------------------

(deftest emit-nil-stream-test
  (testing "emit! is safe when event-stream is nil"
    (is (nil? (#'sut/emit! nil {:type :test})))))

(deftest emit-nil-event-test
  (testing "emit! is safe when event is nil"
    (let [es (stream/create-event-stream)]
      ;; Should not throw
      (#'sut/emit! es nil))))

(deftest emit-both-nil-test
  (testing "emit! is safe when both stream and event are nil"
    (is (nil? (#'sut/emit! nil nil)))))

(deftest emit-with-valid-stream-and-event-test
  (testing "emit! publishes event when stream is present"
    (let [es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))]
      (#'sut/emit! es {:event/type :test :data "hello"})
      (Thread/sleep 50)
      (is (>= (count @published) 1)
          "should have published the event"))))

;; ---------------------------------------------------------------------------
;; Layer 1: Discovery pass (unit-level, calling private fn via #')
;; ---------------------------------------------------------------------------

(deftest run-discovery-pass-registers-new-agents-test
  (testing "discovery pass registers newly discovered agents"
    (let [reg (registry/create-registry)
          discovered (atom [])
          agent-info {:agent/external-id "ext-1"
                      :agent/name "Test Agent"
                      :agent/vendor :test-adapter
                      :agent/capabilities #{:code-generation}}
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [agent-info])})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :adapters [adapter]
                  :on-agent-discovered #(swap! discovered conj %)}))]
      (#'sut/run-discovery-pass orch)
      (is (= 1 (registry/count-agents reg))
          "agent should be registered")
      (is (= 1 (count @discovered))
          "on-agent-discovered callback should have been called")
      (is (= "Test Agent" (:agent/name (first @discovered)))))))

(deftest run-discovery-pass-skips-known-agents-test
  (testing "discovery pass does not re-register known agents"
    (let [reg (registry/create-registry)
          discovered (atom [])
          agent-info {:agent/external-id "ext-1"
                      :agent/name "Test Agent"
                      :agent/vendor :test-adapter}
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [agent-info])})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :adapters [adapter]
                  :on-agent-discovered #(swap! discovered conj %)}))]
      ;; First pass registers
      (#'sut/run-discovery-pass orch)
      ;; Second pass should skip
      (#'sut/run-discovery-pass orch)
      (is (= 1 (registry/count-agents reg))
          "should not duplicate registration")
      (is (= 1 (count @discovered))
          "callback should only fire once"))))

(deftest run-discovery-pass-handles-adapter-exception-test
  (testing "discovery pass catches exceptions from adapter"
    (let [reg (registry/create-registry)
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] (throw (Exception. "boom")))})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      ;; Should not throw
      (is (nil? (#'sut/run-discovery-pass orch))))))

(deftest run-discovery-pass-multiple-adapters-test
  (testing "discovery across multiple adapters"
    (let [reg (registry/create-registry)
          a1 (make-mock-adapter
              {:adapter-id :adapter-1
               :discover-agents (fn [_] [{:agent/external-id "a1-ext"
                                          :agent/name "Agent 1"
                                          :agent/vendor :adapter-1}])})
          a2 (make-mock-adapter
              {:adapter-id :adapter-2
               :discover-agents (fn [_] [{:agent/external-id "a2-ext"
                                          :agent/name "Agent 2"
                                          :agent/vendor :adapter-2}])})
          orch (sut/create-orchestrator
                (base-orchestrator-opts {:registry reg :adapters [a1 a2]}))]
      (#'sut/run-discovery-pass orch)
      (is (= 2 (registry/count-agents reg))))))

(deftest run-discovery-pass-with-event-stream-test
  (testing "discovery emits agent-registered events when stream is present"
    (let [reg (registry/create-registry)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          agent-info {:agent/external-id "ext-1"
                      :agent/name "Test Agent"
                      :agent/vendor :test-adapter}
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [agent-info])})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :adapters [adapter]
                  :event-stream es}))]
      (#'sut/run-discovery-pass orch)
      ;; Give async publish a moment
      (Thread/sleep 50)
      (is (>= (count @published) 1)
          "should have published at least one event"))))

(deftest run-discovery-pass-empty-results-test
  (testing "discovery pass handles adapter returning empty list"
    (let [reg (registry/create-registry)
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [])})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      (#'sut/run-discovery-pass orch)
      (is (zero? (registry/count-agents reg))
          "no agents should be registered when none discovered"))))

(deftest run-discovery-pass-multiple-agents-one-adapter-test
  (testing "discovery pass registers multiple agents from a single adapter"
    (let [reg (registry/create-registry)
          discovered (atom [])
          agents [{:agent/external-id "ext-a" :agent/name "Agent A" :agent/vendor :test-adapter}
                  {:agent/external-id "ext-b" :agent/name "Agent B" :agent/vendor :test-adapter}
                  {:agent/external-id "ext-c" :agent/name "Agent C" :agent/vendor :test-adapter}]
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] agents)})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :adapters [adapter]
                  :on-agent-discovered #(swap! discovered conj %)}))]
      (#'sut/run-discovery-pass orch)
      (is (= 3 (registry/count-agents reg))
          "all three agents should be registered")
      (is (= 3 (count @discovered))
          "callback should fire for each new agent"))))

(deftest run-discovery-pass-partial-failure-test
  (testing "discovery continues with second adapter when first throws"
    (let [reg (registry/create-registry)
          failing-adapter (make-mock-adapter
                           {:adapter-id :failing
                            :discover-agents (fn [_] (throw (Exception. "fail")))})
          ok-adapter (make-mock-adapter
                      {:adapter-id :ok-adapter
                       :discover-agents (fn [_] [{:agent/external-id "ok-ext"
                                                   :agent/name "OK Agent"
                                                   :agent/vendor :ok-adapter}])})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [failing-adapter ok-adapter]}))]
      (#'sut/run-discovery-pass orch)
      (is (= 1 (registry/count-agents reg))
          "should still register agents from non-failing adapter"))))

;; ---------------------------------------------------------------------------
;; Layer 1: Poll pass
;; ---------------------------------------------------------------------------

(deftest run-poll-pass-updates-agent-status-test
  (testing "poll pass records heartbeat and detects status changes"
    (let [reg (registry/create-registry)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-poll"
                      :agent/name "Poll Agent"
                      :agent/vendor :test-adapter})
          agent-id (:agent/id agent-rec)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_agent-record]
                      {:agent/status :running
                       :agent/task "working on stuff"})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (let [updated (registry/get-agent reg agent-id)]
        (is (some? (:agent/last-heartbeat updated))
            "heartbeat should be recorded")))))

(deftest run-poll-pass-skips-terminal-agents-test
  (testing "poll pass skips agents with terminal status"
    (let [reg (registry/create-registry)
          poll-count (atom 0)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-term"
                      :agent/name "Term Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          _ (registry/transition-agent! reg (:agent/id agent-rec) :completed)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] (swap! poll-count inc) {:agent/status :completed})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (is (zero? @poll-count)
          "should not poll terminal agents"))))

(deftest run-poll-pass-skips-failed-agents-test
  (testing "poll pass skips agents with :failed status"
    (let [reg (registry/create-registry)
          poll-count (atom 0)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-fail"
                      :agent/name "Fail Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          _ (registry/transition-agent! reg (:agent/id agent-rec) :failed)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] (swap! poll-count inc) {:agent/status :failed})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (is (zero? @poll-count)
          "should not poll failed agents"))))

(deftest run-poll-pass-skips-terminated-agents-test
  (testing "poll pass skips agents with :terminated status"
    (let [reg (registry/create-registry)
          poll-count (atom 0)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-terminated"
                      :agent/name "Terminated Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          _ (registry/transition-agent! reg (:agent/id agent-rec) :terminated)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] (swap! poll-count inc) nil)})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (is (zero? @poll-count)
          "should not poll terminated agents"))))

(deftest run-poll-pass-handles-poll-exception-test
  (testing "poll pass catches exceptions from adapter poll"
    (let [reg (registry/create-registry)
          _ (registry/register-agent! reg
             {:agent/external-id "ext-err"
              :agent/name "Err Agent"
              :agent/vendor :test-adapter})
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] (throw (Exception. "poll failed")))})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      ;; Should not throw
      (is (nil? (#'sut/run-poll-pass orch))))))

(deftest run-poll-pass-nil-status-update-test
  (testing "poll pass handles nil status update gracefully"
    (let [reg (registry/create-registry)
          _ (registry/register-agent! reg
             {:agent/external-id "ext-nil"
              :agent/name "Nil Agent"
              :agent/vendor :test-adapter})
          adapter (make-mock-adapter
                   {:poll-agent-status (fn [_] nil)})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      ;; Should not throw when poll returns nil
      (is (nil? (#'sut/run-poll-pass orch))))))

(deftest run-poll-pass-with-event-stream-emits-on-status-change-test
  (testing "poll pass emits agent-state-changed event when status changes"
    (let [reg (registry/create-registry)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-state"
                      :agent/name "State Agent"
                      :agent/vendor :test-adapter})
          ;; Agent starts as :initializing, poll returns :running
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] {:agent/status :running})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :adapters [adapter]
                  :event-stream es}))]
      (#'sut/run-poll-pass orch)
      (Thread/sleep 100)
      ;; Should have emitted a state-changed event since status went from :initializing to :running
      (is (>= (count @published) 1)
          "should have published at least one state-changed event"))))

(deftest run-poll-pass-no-event-when-status-unchanged-test
  (testing "poll pass does not emit event when status remains the same"
    (let [reg (registry/create-registry)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-same"
                      :agent/name "Same Agent"
                      :agent/vendor :test-adapter})
          ;; Agent starts as :initializing, poll returns :initializing (same)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] {:agent/status :initializing})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :adapters [adapter]
                  :event-stream es}))]
      (#'sut/run-poll-pass orch)
      (Thread/sleep 100)
      ;; Status didn't change, so no state-changed event expected
      (is (zero? (count @published))
          "should not publish event when status is unchanged"))))

(deftest run-poll-pass-multiple-vendors-test
  (testing "poll pass routes agents to correct adapters by vendor"
    (let [reg (registry/create-registry)
          poll-log (atom [])
          _ (registry/register-agent! reg
             {:agent/external-id "ext-v1" :agent/name "V1 Agent" :agent/vendor :vendor-1})
          _ (registry/register-agent! reg
             {:agent/external-id "ext-v2" :agent/name "V2 Agent" :agent/vendor :vendor-2})
          a1 (make-mock-adapter
              {:adapter-id :vendor-1
               :poll-agent-status (fn [agent-rec]
                                    (swap! poll-log conj [:vendor-1 (:agent/name agent-rec)])
                                    {:agent/status :running})})
          a2 (make-mock-adapter
              {:adapter-id :vendor-2
               :poll-agent-status (fn [agent-rec]
                                    (swap! poll-log conj [:vendor-2 (:agent/name agent-rec)])
                                    {:agent/status :running})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [a1 a2]}))]
      (#'sut/run-poll-pass orch)
      (is (= 2 (count @poll-log))
          "each agent should be polled once")
      (is (some #(= [:vendor-1 "V1 Agent"] %) @poll-log))
      (is (some #(= [:vendor-2 "V2 Agent"] %) @poll-log)))))

(deftest run-poll-pass-unmatched-vendor-test
  (testing "poll pass skips agents whose vendor has no matching adapter"
    (let [reg (registry/create-registry)
          poll-count (atom 0)
          _ (registry/register-agent! reg
             {:agent/external-id "ext-orphan" :agent/name "Orphan Agent" :agent/vendor :unknown-vendor})
          adapter (make-mock-adapter
                   {:adapter-id :other-vendor
                    :poll-agent-status (fn [_] (swap! poll-count inc) {:agent/status :running})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (is (zero? @poll-count)
          "should not poll agents with unmatched vendor"))))

;; ---------------------------------------------------------------------------
;; Layer 2: start! and stop!
;; ---------------------------------------------------------------------------

(deftest start-stop-lifecycle-test
  (testing "start! sets running to true and stop! cleans up"
    (let [adapter (make-mock-adapter {})
          orch (sut/create-orchestrator
                (base-orchestrator-opts {:adapters [adapter]}))
          started (sut/start! orch)]
      (is (true? @(:running orch))
          "should be running after start")
      (is (>= (count @(:futures orch)) 2)
          "should have at least discovery + poll futures")
      ;; Stop
      (let [stopped (sut/stop! started)]
        (is (false? @(:running stopped))
            "should not be running after stop")
        (is (empty? @(:futures stopped))
            "futures should be cleared after stop")))))

(deftest start-runs-discovery-test
  (testing "start! triggers discovery loop that finds agents"
    (let [reg (registry/create-registry)
          discovered (atom [])
          agent-info {:agent/external-id "ext-loop"
                      :agent/name "Loop Agent"
                      :agent/vendor :test-adapter}
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [agent-info])})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :adapters [adapter]
                  :on-agent-discovered #(swap! discovered conj %)
                  :discovery-interval-ms 50
                  :poll-interval-ms 50}))]
      (sut/start! orch)
      ;; Wait for at least one discovery cycle
      (Thread/sleep 200)
      (sut/stop! orch)
      (is (pos? (registry/count-agents reg))
          "discovery loop should have registered agents")
      (is (pos? (count @discovered))
          "on-agent-discovered should have been called"))))

(deftest stop-idempotent-test
  (testing "stop! can be called multiple times safely"
    (let [orch (sut/create-orchestrator (base-orchestrator-opts))]
      (sut/start! orch)
      (sut/stop! orch)
      ;; Second stop should not throw
      (is (some? (sut/stop! orch))))))

(deftest start-returns-orchestrator-with-watchdog-test
  (testing "start! returns orchestrator with :watchdog key"
    (let [orch (sut/create-orchestrator (base-orchestrator-opts))
          started (sut/start! orch)]
      (is (some? (:watchdog started))
          "should have a watchdog after start")
      (sut/stop! started))))

(deftest stop-clears-running-flag-immediately-test
  (testing "stop! sets running to false immediately"
    (let [orch (sut/create-orchestrator (base-orchestrator-opts))]
      (sut/start! orch)
      (is (true? @(:running orch)))
      (sut/stop! orch)
      (is (false? @(:running orch))))))

(deftest stop-on-never-started-orchestrator-test
  (testing "stop! on a never-started orchestrator is safe"
    (let [orch (sut/create-orchestrator (base-orchestrator-opts))]
      (is (some? (sut/stop! orch))
          "should return orchestrator without error")
      (is (false? @(:running orch))))))

;; ---------------------------------------------------------------------------
;; Layer 2: submit-decision-from-agent!
;; ---------------------------------------------------------------------------

(deftest submit-decision-from-agent-test
  (testing "submits a decision, transitions agent to :blocked, fires callback"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          callback-decisions (atom [])
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-dec"
                      :agent/name "Dec Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :decision-manager dm
                  :on-decision-created #(swap! callback-decisions conj %)}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Merge PR?")]
      (is (some? decision)
          "should return the decision")
      (is (uuid? (:decision/id decision))
          "decision should have an ID")
      (is (= "Merge PR?" (:decision/summary decision))
          "decision should have the summary")
      (is (= (:agent/id agent-rec) (:decision/agent-id decision))
          "decision should reference the agent")
      ;; Agent should be blocked
      (let [agent (registry/get-agent reg (:agent/id agent-rec))]
        (is (= :blocked (:agent/status agent))
            "agent should be transitioned to :blocked"))
      ;; Callback should have fired
      (is (= 1 (count @callback-decisions)))
      ;; Decision should be in the manager
      (is (some? (dq/get-decision dm (:decision/id decision)))))))

(deftest submit-decision-with-opts-test
  (testing "submit-decision-from-agent! passes opts to create-decision"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-opts"
                      :agent/name "Opts Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :decision-manager dm}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Choose deploy target"
                    {:decision/type :choice
                     :decision/priority :high
                     :decision/options ["staging" "production"]})]
      (is (= :high (:decision/priority decision)))
      (is (= :choice (:decision/type decision))))))

(deftest submit-decision-terminal-agent-test
  (testing "submit-decision-from-agent! handles already-terminal agent gracefully"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-done"
                      :agent/name "Done Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          _ (registry/transition-agent! reg (:agent/id agent-rec) :completed)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :decision-manager dm}))
          ;; Should not throw even though transition to :blocked will fail
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Too late?")]
      (is (some? decision)
          "decision should still be created even if transition fails"))))

(deftest submit-decision-with-event-stream-test
  (testing "submit-decision-from-agent! emits decision-created event"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-ev-dec"
                      :agent/name "Event Dec Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :decision-manager dm
                  :event-stream es}))]
      (sut/submit-decision-from-agent!
       orch (:agent/id agent-rec) "Event decision?")
      (Thread/sleep 100)
      (is (>= (count @published) 1)
          "should have published a decision-created event"))))

(deftest submit-multiple-decisions-same-agent-test
  (testing "can submit multiple decisions for the same agent"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-multi"
                      :agent/name "Multi Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :decision-manager dm}))
          d1 (sut/submit-decision-from-agent!
              orch (:agent/id agent-rec) "First decision")
          d2 (sut/submit-decision-from-agent!
              orch (:agent/id agent-rec) "Second decision")]
      (is (some? d1))
      (is (some? d2))
      (is (not= (:decision/id d1) (:decision/id d2))
          "decisions should have distinct IDs")
      (is (= 2 (count (dq/decisions-for-agent dm (:agent/id agent-rec))))
          "both decisions should be in the manager"))))

;; ---------------------------------------------------------------------------
;; Layer 2: resolve-and-deliver!
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-test
  (testing "resolves decision, delivers to agent, transitions back to :running"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          delivered (atom nil)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-res"
                      :agent/name "Resolve Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          adapter (make-mock-adapter
                   {:deliver-decision
                    (fn [agent-record decision]
                      (reset! delivered {:agent agent-record :decision decision})
                      {:delivered? true})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :decision-manager dm
                  :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Ship it?")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes" "LGTM")]
      (is (some? (:resolved result))
          "should return resolved decision")
      (is (true? (:delivered? result))
          "should report successful delivery")
      (is (some? @delivered)
          "adapter deliver-decision should have been called")
      ;; Agent should be back to running
      (let [agent (registry/get-agent reg (:agent/id agent-rec))]
        (is (= :running (:agent/status agent))
            "agent should be transitioned back to :running")))))

(deftest resolve-and-deliver-unknown-decision-test
  (testing "resolve-and-deliver! returns nil for unknown decision ID"
    (let [orch (sut/create-orchestrator (base-orchestrator-opts))
          result (sut/resolve-and-deliver!
                  orch (random-uuid) "yes")]
      (is (nil? result)
          "should return nil when decision not found"))))

(deftest resolve-and-deliver-no-adapter-test
  (testing "resolve-and-deliver! handles missing adapter (delivery fails)"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-noadapt"
                      :agent/name "No Adapter Agent"
                      :agent/vendor :missing-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :decision-manager dm
                  :adapters []}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "No adapter?")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes")]
      (is (some? (:resolved result))
          "decision should still be resolved")
      (is (false? (:delivered? result))
          "delivery should be false when no adapter matches"))))

(deftest resolve-and-deliver-with-event-stream-test
  (testing "resolve-and-deliver! emits decision-resolved event"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-ev"
                      :agent/name "Event Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :decision-manager dm
                  :adapters [adapter]
                  :event-stream es}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Event test?")
          _ (sut/resolve-and-deliver!
             orch (:decision/id decision) "approved")]
      (Thread/sleep 100)
      ;; Should have events for: decision-created, decision-resolved
      (is (>= (count @published) 2)
          "should have published decision-created and decision-resolved events"))))

(deftest resolve-and-deliver-without-comment-test
  (testing "resolve-and-deliver! works without optional comment"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-nocomm"
                      :agent/name "No Comment Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :decision-manager dm :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "No comment")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes")]
      (is (some? (:resolved result)))
      (is (true? (:delivered? result))))))

(deftest resolve-and-deliver-adapter-delivery-failure-test
  (testing "resolve-and-deliver! reports delivered? false when adapter returns failure"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? false :error "timeout"})})
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-delfail"
                      :agent/name "Del Fail Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :decision-manager dm :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Will fail delivery")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes")]
      (is (some? (:resolved result))
          "decision should still be resolved")
      (is (false? (:delivered? result))
          "delivered? should be false when adapter reports failure"))))

(deftest resolve-and-deliver-resolved-data-contains-resolution-test
  (testing "the resolved decision contains the resolution value"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (registry/register-agent! reg
                     {:agent/external-id "ext-resdata"
                      :agent/name "ResData Agent"
                      :agent/vendor :test-adapter})
          _ (registry/transition-agent! reg (:agent/id agent-rec) :running)
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :decision-manager dm :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Check resolution data")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "approved" "Looks good")]
      (is (= "approved" (:decision/resolution (:resolved result))))
      (is (= "Looks good" (:decision/comment (:resolved result)))))))

;; ---------------------------------------------------------------------------
;; Integration: full lifecycle
;; ---------------------------------------------------------------------------

(deftest full-lifecycle-integration-test
  (testing "end-to-end: create -> start -> discover -> decide -> resolve -> stop"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-info {:agent/external-id "ext-e2e"
                      :agent/name "E2E Agent"
                      :agent/vendor :test-adapter}
          deliver-log (atom [])
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [agent-info])
                    :poll-agent-status (fn [_] {:agent/status :running})
                    :deliver-decision (fn [_ d]
                                       (swap! deliver-log conj d)
                                       {:delivered? true})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :decision-manager dm
                  :adapters [adapter]
                  :discovery-interval-ms 50
                  :poll-interval-ms 50}))]

      ;; Start orchestrator
      (sut/start! orch)
      ;; Wait for discovery
      (Thread/sleep 200)

      (let [agents (registry/list-agents reg)]
        (is (= 1 (count agents)) "should have discovered one agent")

        (let [agent-id (:agent/id (first agents))
              ;; Transition agent to running so we can submit decision
              _ (try (registry/transition-agent! reg agent-id :running)
                     (catch Exception _ nil))
              decision (sut/submit-decision-from-agent!
                        orch agent-id "Deploy to prod?")
              _ (is (some? decision))
              result (sut/resolve-and-deliver!
                      orch (:decision/id decision) "approved" "Go for it")]
          (is (true? (:delivered? result)))
          (is (= 1 (count @deliver-log)))))

      ;; Stop orchestrator
      (sut/stop! orch)
      (is (false? @(:running orch))))))

(deftest full-lifecycle-with-event-stream-integration-test
  (testing "end-to-end with event stream captures all events"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          agent-info {:agent/external-id "ext-e2e-es"
                      :agent/name "E2E ES Agent"
                      :agent/vendor :test-adapter}
          adapter (make-mock-adapter
                   {:discover-agents (fn [_] [agent-info])
                    :poll-agent-status (fn [_] {:agent/status :running})
                    :deliver-decision (fn [_ _] {:delivered? true})})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg
                  :decision-manager dm
                  :adapters [adapter]
                  :event-stream es
                  :discovery-interval-ms 50
                  :poll-interval-ms 50}))]
      (sut/start! orch)
      (Thread/sleep 200)

      (let [agents (registry/list-agents reg)
            agent-id (:agent/id (first agents))
            _ (try (registry/transition-agent! reg agent-id :running)
                   (catch Exception _ nil))
            decision (sut/submit-decision-from-agent!
                      orch agent-id "Full lifecycle event test")
            _ (sut/resolve-and-deliver!
               orch (:decision/id decision) "yes")]
        (Thread/sleep 100)
        ;; Should have: agent-registered, possibly state-changed, decision-created, decision-resolved
        (is (>= (count @published) 3)
            "should have published multiple events throughout lifecycle"))

      (sut/stop! orch))))

;; ---------------------------------------------------------------------------
;; Edge cases
;; ---------------------------------------------------------------------------

(deftest create-orchestrator-nil-adapters-test
  (testing "nil adapters defaults to empty vector"
    (let [orch (sut/create-orchestrator {:adapters nil})]
      (is (= [] (:adapters orch))))))

(deftest discovery-with-no-adapters-test
  (testing "discovery pass with no adapters is a no-op"
    (let [reg (registry/create-registry)
          orch (sut/create-orchestrator
                (base-orchestrator-opts {:registry reg}))]
      (#'sut/run-discovery-pass orch)
      (is (zero? (registry/count-agents reg))))))

(deftest poll-pass-with-no-agents-test
  (testing "poll pass with empty registry is a no-op"
    (let [reg (registry/create-registry)
          poll-count (atom 0)
          adapter (make-mock-adapter
                   {:poll-agent-status (fn [_] (swap! poll-count inc) nil)})
          orch (sut/create-orchestrator
                (base-orchestrator-opts
                 {:registry reg :adapters [adapter]}))]
      (#'sut/run-poll-pass orch)
      (is (zero? @poll-count)
          "should not call poll when registry is empty"))))

(deftest poll-pass-with-no-adapters-test
  (testing "poll pass with no adapters is safe"
    (let [reg (registry/create-registry)
          _ (registry/register-agent! reg
             {:agent/external-id "ext-lonely" :agent/name "Lonely" :agent/vendor :no-adapter})
          orch (sut/create-orchestrator
                (base-orchestrator-opts {:registry reg :adapters []}))]
      ;; Should not throw
      (is (nil? (#'sut/run-poll-pass orch))))))

(deftest create-orchestrator-empty-opts-test
  (testing "create-orchestrator works with completely empty opts map"
    (let [orch (sut/create-orchestrator {})]
      (is (some? orch))
      (is (some? (:registry orch)))
      (is (some? (:decision-manager orch)))
      (is (= [] (:adapters orch)))
      (is (instance? clojure.lang.Atom (:running orch)))
      (is (instance? clojure.lang.Atom (:futures orch))))))
