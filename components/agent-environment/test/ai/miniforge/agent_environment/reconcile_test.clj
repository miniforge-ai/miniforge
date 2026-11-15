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
(ns ai.miniforge.agent-environment.reconcile-test
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.fake :as fake]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger-faults :as faults :refer [outage-world]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Duration Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} unreachable
  (anomaly/sub-anomaly :unavailable :provider/unreachable "unreachable" {}))

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} states [w] (mapv :entry/state (ledger/all-entries (:inner w))))

(defn- ^{:stratum 0} reconcile-steps
  [w]
  (->> @(:receipts w) (filter #(= :environment/reconcile (:receipt/operation %))) (mapv :receipt/steps)))

(defn- ^{:stratum 0} abandon!
  "Release `k`'s reservation in `inner`, as a controller that reconciled it
   after its claim expired and found nothing would."
  [inner k]
  (let [{:ledger/keys [entry revision]} (store/read-entry inner k)]
    (store/write-entry! inner k revision (lease/reconciled entry nil))))

(defn- ^{:stratum 0} reacquire
  [w c req]
  (env/acquire! c (:a w) (fx/provision-grant w (:a w)) req))

(defn- ^{:stratum 0} later
  "A clock far enough ahead that every claim made now has expired."
  []
  (.plus (Instant/now) (Duration/ofMillis (inc lease/default-claim-ttl-ms))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} abandon-at-record
  "A `provision!` hook: the owner's next ledger write finds its reservation
   already abandoned. It returns nil, so the provider still answers."
  [w k]
  (fn []
    (swap! (:faults w) assoc :before-write (partial abandon! (:inner w) k))
    nil))

(deftest ^{:stratum 1} test-an-unexpired-claim-is-left-to-its-owner
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (controller/claim! (:c w) req)
    (is (= :anomalies.agent-environment/acquire-in-flight
           (reason (reacquire w (faults/controller-over w (get-in w [:c :controller/ledger])) req))))
    (is (= [nil nil] ((juxt :observe :provision!) @(:calls w))))))

(defn- ^{:stratum 1} restarted
  "A controller started after every claim made now expired, over `w`'s ledger."
  [w]
  (faults/controller-over w (get-in w [:c :controller/ledger]) later))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-an-owner-whose-claim-was-reconciled-deletes-what-it-made
  (let [w (outage-world)
        req (fx/request w (:a w))
        k (lease/request-key req)]
    (swap! (:hooks w) assoc :provision! (abandon-at-record w k))
    (is (= :anomalies.agent-environment/claim-lost (reason (fx/acquire w (:a w) req))))
    (testing "given the reservation was abandoned before the owner recorded it → nothing is left running"
      (is (empty? (fake/live-namespaces (:fake w))))
      (is (= [:state/released] (states w)))
      (is (= [:outcome/applied] (fx/outcomes w :environment/release))))))

(deftest ^{:stratum 2} test-a-replayed-acquisition-adopts-what-a-lost-response-created
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (swap! (:faults w) assoc :writes 1)
    (fx/acquire w (:a w) req)
    (swap! (:faults w) assoc :writes nil)
    (let [c (restarted w)
          adopted (reacquire w c req)]
      (is (= 1 (:provision! @(:calls w))))
      (is (= (fake/live-namespaces (:fake w)) #{(:environment/namespace adopted)}))
      (is (= {:readiness/ready? true} (env/readiness c (:a w) (:environment/lease adopted))))
      (is (= [[{:step/name :step/adopted :step/outcome :outcome/applied}]] (reconcile-steps w))))))

(deftest ^{:stratum 2} test-a-reservation-nothing-was-made-for-is-released-then-resubmitted
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (controller/claim! (:c w) req)
    (let [resubmitted (reacquire w (restarted w) req)]
      (is (= 2 (get-in resubmitted [:environment/lease :lease/generation])))
      (is (= 1 (:provision! @(:calls w))))
      (is (= [[{:step/name :step/abandoned :step/outcome :outcome/applied}]] (reconcile-steps w))))))

(deftest ^{:stratum 2} test-a-provider-that-cannot-answer-leaves-the-reservation
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (controller/claim! (:c w) req)
    (swap! (:hooks w) assoc :observe (constantly unreachable))
    (is (= :provider/unreachable (reason (reacquire w (restarted w) req))))
    (is (= [:state/provisioning] (states w)))
    (is (nil? (:provision! @(:calls w))))))
