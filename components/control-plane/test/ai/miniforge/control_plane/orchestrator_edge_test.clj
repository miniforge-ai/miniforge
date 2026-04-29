;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.control-plane.orchestrator-edge-test
  "Edge-case and gap-coverage tests for the orchestrator.
   Complements orchestrator-test and orchestrator-supplemental-test
   by targeting untested branches and boundary conditions."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.control-plane.orchestrator :as sut]
   [ai.miniforge.control-plane.registry :as registry]
   [ai.miniforge.control-plane.decision-queue :as dq]
   [ai.miniforge.control-plane-adapter.protocol :as adapter]
   [ai.miniforge.event-stream.interface.stream :as stream]))

(def ^:private default-wait-timeout-ms 2000)

(defn- wait-until-count
  "Block until `(count @items-atom) >= n` or `timeout-ms` elapses.
   Uses add-watch + promise — no fixed sleep. Returns true if reached, false on timeout."
  ([items-atom n] (wait-until-count items-atom n default-wait-timeout-ms))
  ([items-atom n timeout-ms]
   (let [done (promise)
         k    (gensym "wait-count-")]
     (when (>= (count @items-atom) n)
       (deliver done :immediate))
     (add-watch items-atom k
                (fn [_ _ _ new-val]
                  (when (>= (count new-val) n)
                    (deliver done :reached))))
     (let [result (deref done timeout-ms ::timeout)]
       (remove-watch items-atom k)
       (not= result ::timeout)))))

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
;; resolve-and-deliver!: no matching adapter for agent's vendor
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-no-matching-adapter-test
  (testing "resolve-and-deliver! returns delivered?=false when no adapter matches vendor"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          ;; Adapter with a different vendor than the agent
          adapter (make-mock-adapter
                   {:adapter-id :other-vendor
                    :deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-no-match"
                      :agent/name "No Match Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "No adapter?")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes")]
      (is (some? (:resolved result))
          "decision should still be resolved")
      (is (false? (:delivered? result))
          "delivered? should be false when no adapter matches"))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: no adapters at all
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-no-adapters-test
  (testing "resolve-and-deliver! works with zero adapters (delivered?=false)"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-no-adapt"
                      :agent/name "No Adapter Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters []}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "No adapters")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "approved")]
      (is (some? (:resolved result)))
      (is (false? (:delivered? result))))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: emits decision-resolved event
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-emits-decision-resolved-event-test
  (testing "resolve-and-deliver! emits :control-plane/decision-resolved event"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-ev-resolve"
                      :agent/name "Resolve Ev Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]
                            :event-stream es}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Resolve event test")
          ;; Wait for submit-emitted events, then clear before resolve.
          _ (wait-until-count published 1)
          _ (reset! published [])
          _ (sut/resolve-and-deliver!
             orch (:decision/id decision) "approved")]
      (is (wait-until-count published 1)
          "should have published decision-resolved event"))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: nonexistent decision-id
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-nonexistent-decision-test
  (testing "resolve-and-deliver! returns nil for a nonexistent decision-id"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          orch (sut/create-orchestrator
                (base-opts {:registry reg :decision-manager dm}))
          bogus-id (random-uuid)
          result (sut/resolve-and-deliver! orch bogus-id "yes")]
      (is (nil? result)
          "should return nil when decision does not exist"))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: with a string comment
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-with-comment-test
  (testing "resolve-and-deliver! passes comment through to resolved decision"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-comment"
                      :agent/name "Comment Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "With comment")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "approved" "LGTM, ship it")]
      (is (= "LGTM, ship it" (:decision/comment (:resolved result)))
          "comment should be preserved in resolved decision")
      (is (= "approved" (:decision/resolution (:resolved result)))))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: adapter returns delivered?=false
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-adapter-returns-not-delivered-test
  (testing "resolve-and-deliver! surfaces delivered?=false from adapter"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? false
                                                  :error "agent busy"})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-not-del"
                      :agent/name "Not Delivered Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Won't deliver")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes")]
      (is (some? (:resolved result)))
      (is (false? (:delivered? result))
          "should reflect adapter's delivered?=false"))))

;; ---------------------------------------------------------------------------
;; submit-decision-from-agent!: without event-stream (nil)
;; ---------------------------------------------------------------------------

(deftest submit-decision-no-event-stream-test
  (testing "submit-decision-from-agent! works fine without event-stream"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-no-es"
                      :agent/name "No ES Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            ;; no :event-stream key
                            }))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "No stream")]
      (is (some? decision))
      (is (= :pending (:decision/status decision)))
      (is (= :blocked (:agent/status (registry/get-agent reg (:agent/id agent-rec))))))))

;; ---------------------------------------------------------------------------
;; resolve-and-deliver!: without event-stream (nil)
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-no-event-stream-test
  (testing "resolve-and-deliver! works fine without event-stream"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-no-es-r"
                      :agent/name "No ES Resolve Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "No stream resolve")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes")]
      (is (some? (:resolved result)))
      (is (true? (:delivered? result))))))

;; ---------------------------------------------------------------------------
;; Discovery pass: no adapters configured
;; ---------------------------------------------------------------------------

(deftest run-discovery-pass-no-adapters-test
  (testing "discovery pass with no adapters is a no-op"
    (let [reg (registry/create-registry)
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters []}))]
      (is (nil? (#'sut/run-discovery-pass orch)))
      (is (zero? (registry/count-agents reg))))))

;; ---------------------------------------------------------------------------
;; Poll pass: no agents registered
;; ---------------------------------------------------------------------------

(deftest run-poll-pass-no-agents-test
  (testing "poll pass with no agents registered is a no-op"
    (let [reg (registry/create-registry)
          adapter (make-mock-adapter {})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))]
      (is (nil? (#'sut/run-poll-pass orch))))))

;; ---------------------------------------------------------------------------
;; Poll pass: uses :status key from status-update (not :agent/status)
;; ---------------------------------------------------------------------------

(deftest run-poll-pass-uses-status-key-test
  (testing "poll pass extracts new-status from :status key in status-update"
    (let [reg (registry/create-registry)
          es (stream/create-event-stream)
          published (atom [])
          _ (stream/subscribe! es :test-sub #(swap! published conj %))
          _agent-rec (registry/register-agent! reg
                      {:agent/external-id "ext-status-key"
                       :agent/name "Status Key Agent"
                       :agent/vendor :test-adapter})
          ;; Return :status (not :agent/status) matching the code's (:status status-update)
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [_] {:status :running
                             :task "coding"})})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :adapters [adapter]
                            :event-stream es}))]
      (#'sut/run-poll-pass orch)
      ;; Agent was :initializing, poll returned :status :running
      ;; This should trigger a state-changed event
      (is (wait-until-count published 1)
          "should emit state-changed when :status key differs from current"))))

;; ---------------------------------------------------------------------------
;; create-orchestrator: nil adapters normalizes to empty vector
;; ---------------------------------------------------------------------------

(deftest create-orchestrator-nil-adapters-test
  (testing "nil adapters is normalized to empty vector"
    (let [orch (sut/create-orchestrator {:adapters nil})]
      (is (vector? (:adapters orch)))
      (is (empty? (:adapters orch))))))

;; ---------------------------------------------------------------------------
;; create-orchestrator: preserves event-stream reference
;; ---------------------------------------------------------------------------

(deftest create-orchestrator-event-stream-nil-by-default-test
  (testing "event-stream is nil when not provided"
    (let [orch (sut/create-orchestrator {})]
      (is (nil? (:event-stream orch))))))

;; ---------------------------------------------------------------------------
;; submit then resolve full round-trip with callbacks
;; ---------------------------------------------------------------------------

(deftest full-decision-round-trip-callbacks-test
  (testing "decision round-trip fires on-decision-created callback"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          created-decisions (atom [])
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:delivered? true})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-roundtrip"
                      :agent/name "Roundtrip Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]
                            :on-decision-created #(swap! created-decisions conj %)}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Round trip test")]
      ;; Verify callback was called with the decision
      (is (= 1 (count @created-decisions)))
      (is (= (:decision/id decision) (:decision/id (first @created-decisions))))
      ;; Verify agent is blocked
      (is (= :blocked (:agent/status (registry/get-agent reg (:agent/id agent-rec)))))
      ;; Resolve and verify return
      (let [result (sut/resolve-and-deliver!
                    orch (:decision/id decision) "go" "approved")]
        (is (true? (:delivered? result)))
        (is (= :running (:agent/status (registry/get-agent reg (:agent/id agent-rec)))))))))

;; ---------------------------------------------------------------------------
;; Poll pass: adapter returns map without :delivered? key
;; ---------------------------------------------------------------------------

(deftest resolve-and-deliver-adapter-missing-delivered-key-test
  (testing "resolve-and-deliver! defaults to delivered?=false when key is missing"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          adapter (make-mock-adapter
                   {:deliver-decision (fn [_ _] {:status :ok})})
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-miss-key"
                      :agent/name "Missing Key Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg
                            :decision-manager dm
                            :adapters [adapter]}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Missing key")
          result (sut/resolve-and-deliver!
                  orch (:decision/id decision) "yes")]
      (is (false? (:delivered? result))
          "should default to false when adapter doesn't return :delivered?"))))

;; ---------------------------------------------------------------------------
;; start!/stop!: watchdog key is present after start
;; ---------------------------------------------------------------------------

(deftest start-returns-orchestrator-map-with-all-keys-test
  (testing "start! returns orchestrator with all expected keys including :watchdog"
    (let [orch (sut/create-orchestrator (base-opts))
          started (sut/start! orch)]
      (is (contains? started :watchdog))
      (is (contains? started :registry))
      (is (contains? started :decision-manager))
      (is (contains? started :adapters))
      (is (true? @(:running started)))
      (sut/stop! started))))

;; ---------------------------------------------------------------------------
;; submit-decision-from-agent!: decision has correct priority default
;; ---------------------------------------------------------------------------

(deftest submit-decision-default-priority-test
  (testing "submit-decision-from-agent! without priority opts uses default"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-prio"
                      :agent/name "Prio Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :decision-manager dm}))
          decision (sut/submit-decision-from-agent!
                    orch (:agent/id agent-rec) "Default prio")]
      (is (some? (:decision/priority decision))
          "decision should have a priority even without explicit opts"))))

;; ---------------------------------------------------------------------------
;; Multiple decisions from same agent
;; ---------------------------------------------------------------------------

(deftest multiple-decisions-same-agent-test
  (testing "an agent can submit multiple decisions (second while already blocked)"
    (let [reg (registry/create-registry)
          dm (dq/create-decision-manager)
          agent-rec (register-running-agent! reg
                     {:agent/external-id "ext-multi"
                      :agent/name "Multi Agent"
                      :agent/vendor :test-adapter})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :decision-manager dm}))
          d1 (sut/submit-decision-from-agent!
              orch (:agent/id agent-rec) "First question")
          ;; Agent is now :blocked; submitting another should still work
          ;; (transition to :blocked from :blocked may throw but is caught)
          d2 (sut/submit-decision-from-agent!
              orch (:agent/id agent-rec) "Second question")]
      (is (some? d1))
      (is (some? d2))
      (is (not= (:decision/id d1) (:decision/id d2))
          "each decision should have a unique ID")
      (is (= 2 (dq/count-pending dm))
          "both decisions should be pending"))))

;; ---------------------------------------------------------------------------
;; Poll pass: adapter returns nil for some agents, status for others
;; ---------------------------------------------------------------------------

(deftest run-poll-pass-mixed-nil-and-status-test
  (testing "poll handles mix of nil and valid status from adapter"
    (let [reg (registry/create-registry)
          _ (registry/register-agent! reg
             {:agent/external-id "ext-nil-poll"
              :agent/name "Nil Poll Agent"
              :agent/vendor :test-adapter})
          _ (registry/register-agent! reg
             {:agent/external-id "ext-ok-poll"
              :agent/name "OK Poll Agent"
              :agent/vendor :test-adapter})
          adapter (make-mock-adapter
                   {:poll-agent-status
                    (fn [agent-rec]
                      (if (= "Nil Poll Agent" (:agent/name agent-rec))
                        nil
                        {:status :running}))})
          orch (sut/create-orchestrator
                (base-opts {:registry reg :adapters [adapter]}))]
      ;; Should not throw
      (is (nil? (#'sut/run-poll-pass orch))))))

;; ---------------------------------------------------------------------------
;; stop!: returns the orchestrator
;; ---------------------------------------------------------------------------

(deftest stop-returns-orchestrator-test
  (testing "stop! returns the orchestrator map"
    (let [orch (sut/create-orchestrator (base-opts))
          started (sut/start! orch)
          stopped (sut/stop! started)]
      (is (map? stopped))
      (is (contains? stopped :registry))
      (is (false? @(:running stopped))))))
