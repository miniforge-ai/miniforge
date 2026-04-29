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

(ns ai.miniforge.control-plane-adapter.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.control-plane-adapter.interface :as sut]
   [ai.miniforge.control-plane-adapter.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(def ^:private openai-status-map
  {:requires_action :blocked
   :in_progress     :running
   :completed       :completed
   :failed          :failed})

(defn- agent-record
  [& {:as overrides}]
  (merge {:agent/id          (random-uuid)
          :agent/external-id "ext-1"
          :agent/name        "Test Agent"
          :agent/vendor      :test-vendor
          :agent/status      :running}
         overrides))

(defn- mock-adapter
  "Build an adapter from a map of method overrides. Defaults are no-op."
  [{:keys [adapter-id discover-agents poll-agent-status
           deliver-decision send-command]
    :or   {adapter-id        :test-vendor
           discover-agents   (fn [_] [])
           poll-agent-status (fn [_] nil)
           deliver-decision  (fn [_ _] {:delivered? true})
           send-command      (fn [_ _] {:success? true})}}]
  (reify proto/ControlPlaneAdapter
    (adapter-id        [_]            adapter-id)
    (discover-agents   [_ config]     (discover-agents config))
    (poll-agent-status [_ rec]        (poll-agent-status rec))
    (deliver-decision  [_ rec res]    (deliver-decision rec res))
    (send-command      [_ rec cmd]    (send-command rec cmd))))

;------------------------------------------------------------------------------ Layer 1
;; Protocol re-export tests

(deftest interface-re-exports-protocol-test
  (testing "interface namespace re-exports the ControlPlaneAdapter protocol"
    (is (= proto/ControlPlaneAdapter sut/ControlPlaneAdapter))
    (is (= proto/ControlPlaneAdapterLogs sut/ControlPlaneAdapterLogs)))
  (testing "interface namespace re-exports each protocol method"
    (is (= proto/adapter-id        sut/adapter-id))
    (is (= proto/discover-agents   sut/discover-agents))
    (is (= proto/poll-agent-status sut/poll-agent-status))
    (is (= proto/deliver-decision  sut/deliver-decision))
    (is (= proto/send-command      sut/send-command))
    (is (= proto/agent-logs        sut/agent-logs))))

;------------------------------------------------------------------------------ Layer 1
;; normalize-status

(deftest normalize-status-known-mapping-test
  (testing "Known vendor status maps to control-plane keyword"
    (is (= :blocked   (sut/normalize-status "requires_action" openai-status-map)))
    (is (= :running   (sut/normalize-status "in_progress"     openai-status-map)))
    (is (= :completed (sut/normalize-status "completed"       openai-status-map)))
    (is (= :failed    (sut/normalize-status "failed"          openai-status-map)))))

(deftest normalize-status-keyword-input-test
  (testing "Keyword input is preserved (no double-keywording)"
    (is (= :blocked (sut/normalize-status :requires_action openai-status-map)))))

(deftest normalize-status-unknown-test
  (testing "Unmapped status defaults to :unknown"
    (is (= :unknown (sut/normalize-status "weird-state" openai-status-map)))
    (is (= :unknown (sut/normalize-status :weird-state  openai-status-map)))))

(deftest normalize-status-empty-mapping-test
  (testing "Empty mapping returns :unknown for any input"
    (is (= :unknown (sut/normalize-status "anything" {})))))

;------------------------------------------------------------------------------ Layer 1
;; ms-since

(deftest ms-since-past-timestamp-test
  (testing "ms-since returns positive elapsed milliseconds for a past timestamp"
    (let [past (java.util.Date. (- (System/currentTimeMillis) 1000))
          elapsed (sut/ms-since past)]
      (is (number? elapsed))
      (is (>= elapsed 1000) "Should reflect at least the backdate amount"))))

(deftest ms-since-nil-test
  (testing "ms-since returns nil for a nil timestamp"
    (is (nil? (sut/ms-since nil)))))

(deftest ms-since-recent-test
  (testing "ms-since for ~now returns small non-negative ms"
    (let [now-ish (java.util.Date.)
          elapsed (sut/ms-since now-ish)]
      (is (>= elapsed 0)))))

;------------------------------------------------------------------------------ Layer 1
;; heartbeat-interval-for-vendor

(deftest heartbeat-interval-known-vendors-test
  (testing "Each known vendor returns its tuned interval"
    (is (= 15000 (sut/heartbeat-interval-for-vendor :claude-code)))
    (is (= 10000 (sut/heartbeat-interval-for-vendor :miniforge)))
    (is (= 60000 (sut/heartbeat-interval-for-vendor :openai)))
    (is (= 30000 (sut/heartbeat-interval-for-vendor :cursor)))))

(deftest heartbeat-interval-unknown-vendor-test
  (testing "Unknown vendor falls back to 30s default"
    (is (= 30000 (sut/heartbeat-interval-for-vendor :acme-newcomer)))
    (is (= 30000 (sut/heartbeat-interval-for-vendor nil)))))

;------------------------------------------------------------------------------ Layer 2
;; Protocol shape — verify an adapter can be implemented and called via interface fns

(deftest adapter-can-be-invoked-via-interface-test
  (testing "An adapter implementing ControlPlaneAdapter is callable via interface fns"
    (let [discovered (atom nil)
          delivered  (atom nil)
          adapter (mock-adapter
                   {:adapter-id      :acme
                    :discover-agents (fn [config]
                                       (reset! discovered config)
                                       [{:agent/external-id "ext-acme"
                                         :agent/name        "Acme Agent"
                                         :agent/vendor      :acme}])
                    :deliver-decision (fn [rec res]
                                        (reset! delivered [rec res])
                                        {:delivered? true})})]
      (testing "adapter-id"
        (is (= :acme (sut/adapter-id adapter))))
      (testing "discover-agents passes through the config and returns the seq"
        (let [result (sut/discover-agents adapter {:org "acme-corp"})]
          (is (= 1 (count result)))
          (is (= :acme (-> result first :agent/vendor)))
          (is (= {:org "acme-corp"} @discovered))))
      (testing "deliver-decision receives the agent and resolution"
        (let [rec (agent-record :agent/vendor :acme)
              res {:decision/id (random-uuid) :decision/resolution "approve"}
              ret (sut/deliver-decision adapter rec res)]
          (is (true? (:delivered? ret)))
          (is (= [rec res] @delivered)))))))

(deftest adapter-defaults-to-unreachable-when-no-poll-result-test
  (testing "poll-agent-status returning nil signals unreachable"
    (let [adapter (mock-adapter {:poll-agent-status (constantly nil)})]
      (is (nil? (sut/poll-agent-status adapter (agent-record)))))))

(deftest send-command-failure-shape-test
  (testing "send-command returns a failure shape with :error string"
    (let [adapter (mock-adapter
                   {:send-command (fn [_ _]
                                    {:success? false :error "vendor unavailable"})})
          ret (sut/send-command adapter (agent-record) :pause)]
      (is (false? (:success? ret)))
      (is (= "vendor unavailable" (:error ret))))))
