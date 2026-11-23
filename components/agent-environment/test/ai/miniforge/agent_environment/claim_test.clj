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
(ns ai.miniforge.agent-environment.claim-test
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger-faults :as faults :refer [outage-world]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} short-ttl-ms
  "A claim TTL distinct from the default, so the option is seen to apply."
  1000)

(def ^{:stratum 0} start
  "The moment these tests' clocks read while a claim is live."
  (Instant/parse "2026-11-17T00:00:00Z"))

(def ^{:stratum 0} reads-before-dispatch
  "Clock reads an acquire makes before its dispatch check: one for
   admission, one for its claim."
  2)

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} stored [w req] (ledger/entry (:inner w) (lease/request-key req)))

(deftest ^{:stratum 0} test-a-dispatch-the-ledger-cannot-confirm-sends-nothing
  (let [w (outage-world)]
    (swap! (:faults w) assoc :before-write #(swap! (:faults w) assoc :reads? false))
    (let [refused (fx/acquire w (:a w) (fx/request w (:a w)))]
      (is (= "down" (:anomaly/message refused)))
      (is (= (:receipt/id (last @(:receipts w))) (get-in refused [:anomaly/data :receipt/id])))
      (is (nil? (:provision! @(:calls w)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} expiring-after
  "A clock reading `start` for its first `reads` reads and past every claim after."
  [reads]
  (let [seen (atom 0)]
    #(if (<= (swap! seen inc) reads) start (faults/later))))

(defn- ^{:stratum 1} short-claim-controller
  "A controller over `w`'s ledger whose claims last `short-ttl-ms` from `start`."
  [w]
  (faults/controller-over w (get-in w [:c :controller/ledger]) {:clock (constantly start) :claim-ttl-ms short-ttl-ms}))

(deftest ^{:stratum 1} test-a-provisioned-entry-keeps-its-digest-and-drops-its-claim
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (fx/acquire w (:a w) req)
    (is (= [:state/provisioned (lease/request-digest req) nil]
           ((juxt :entry/state :entry/request-digest :entry/claim) (stored w req))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-a-reservation-records-its-claim-and-digest
  (let [w (outage-world)
        req (fx/request w (:a w))
        c (short-claim-controller w)
        expected (lease/claim (:controller/id c) (.plusMillis start short-ttl-ms))]
    (controller/claim! c req)
    (is (= expected (:entry/claim (stored w req))))
    (is (= (lease/request-digest req) (:entry/request-digest (stored w req))))))

(deftest ^{:stratum 2} test-provisioning-waits-for-a-live-claim
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (is (= :anomalies.agent-environment/claim-lost
           (reason (faults/reacquire w (faults/restarted w (expiring-after reads-before-dispatch)) req))))
    (is (nil? (:provision! @(:calls w))))
    (is (= :state/provisioning (:entry/state (stored w req))))))
