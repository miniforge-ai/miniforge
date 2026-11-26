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
(ns ai.miniforge.agent-environment.ledger-lifecycle-test
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.fake :as fake]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger-faults :as faults :refer [outage-world]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} unavailable :anomalies.agent-environment/ledger-unavailable)

(def ^{:stratum 0} mutating-calls
  "Provider calls that change what the provider holds. `probe` and
   `observe` only read, so a refused request may still make them."
  [:provision! :deprovision! :cancel! :exec! :write-file!])

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} second-controller
  [w]
  (faults/controller-over w (get-in w [:c :controller/ledger])))

(defn- ^{:stratum 0} acquire [w] (fx/acquire w (:a w) (fx/request w (:a w))))

(defn- ^{:stratum 0} states [w] (mapv :entry/state (ledger/all-entries (:inner w))))

(defn- ^{:stratum 0} last-receipt [w] ((juxt :receipt/outcome :receipt/reason) (last @(:receipts w))))

(defn- ^{:stratum 0} mark-uncertain!
  [inner current]
  (let [k (lease/lease-key current)
        {:ledger/keys [entry revision]} (store/read-entry inner k)]
    (store/write-entry! inner k revision (assoc entry :entry/state :state/cleanup-uncertain))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} mutations [w] (select-keys @(:calls w) mutating-calls))

(deftest ^{:stratum 1} test-an-unrecorded-claim-never-reaches-the-provider
  (let [w (outage-world)]
    (swap! (:faults w) assoc :writes 0)
    (is (= unavailable (reason (acquire w))))
    (is (nil? (:provision! @(:calls w))))
    (is (= [:outcome/refused unavailable] (last-receipt w)))
    (is (= [] (states w)))))

(deftest ^{:stratum 1} test-a-ledger-refusal-keeps-the-ledger-anomaly
  (let [w (outage-world)
        current (:environment/lease (acquire w))]
    (swap! (:faults w) assoc :writes 0)
    (let [refused (acquire w)]
      (is (= "down" (:anomaly/message refused)))
      (is (= (:receipt/id (last @(:receipts w))) (get-in refused [:anomaly/data :receipt/id]))))
    (testing "given another tenant's lease → refused as forged without reading the ledger"
      (swap! (:faults w) assoc :reads? false :read-count 0)
      (is (= :anomalies.agent-environment/forged-tenant (reason (env/readiness (:c w) (:b w) current))))
      (is (zero? (:read-count @(:faults w)))))))

(deftest ^{:stratum 1} test-a-cancel-whose-lease-was-released-never-reaches-the-provider
  (let [w (outage-world)
        current (:environment/lease (acquire w))
        rival (second-controller w)]
    (swap! (:faults w) assoc :before-write #(env/release! rival (:a w) current))
    (is (= :anomalies.agent-environment/environment-released (reason (env/cancel! (:c w) (:a w) current))))
    (is (nil? (:cancel! @(:calls w))))
    (is (= [:state/released] (states w)))))

(deftest ^{:stratum 1} test-a-cancel-never-overwrites-an-unconfirmed-release
  (let [w (outage-world)
        current (:environment/lease (acquire w))]
    (swap! (:faults w) assoc :before-write #(mark-uncertain! (:inner w) current))
    (is (= :anomalies.agent-environment/cleanup-uncertain (reason (env/cancel! (:c w) (:a w) current))))
    (is (nil? (:cancel! @(:calls w))))
    (is (= [:state/cleanup-uncertain] (states w)))))

(deftest ^{:stratum 1} test-a-provision-the-ledger-misses-hands-out-no-lease
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (swap! (:faults w) assoc :writes 1)
    (is (= unavailable (reason (fx/acquire w (:a w) req))))
    (testing "given a provision the ledger misses → the environment exists and its reservation stays provisioning"
      (is (= 1 (count (fake/live-namespaces (:fake w)))))
      (is (= [:state/provisioning] (states w)))
      (is (= [:outcome/applied unavailable] (last-receipt w))))
    (testing "given the same request again → refused in flight, never provisioned twice"
      (swap! (:faults w) assoc :writes nil)
      (is (= :anomalies.agent-environment/acquire-in-flight (reason (fx/acquire w (:a w) req))))
      (is (= 1 (:provision! @(:calls w)))))))

(deftest ^{:stratum 1} test-leased-calls-wait-for-a-readable-ledger
  (let [w (outage-world)
        current (:environment/lease (acquire w))]
    (swap! (:faults w) assoc :reads? false)
    (is (= unavailable (reason (env/exec! (:c w) (:a w) current fx/probe-command))))
    (is (= unavailable (reason (env/release! (:c w) (:a w) current))))
    (is (= [nil nil] ((juxt :exec! :deprovision!) @(:calls w))))))

(deftest ^{:stratum 1} test-an-unrecorded-cancel-never-reaches-the-provider
  (let [w (outage-world)
        current (:environment/lease (acquire w))]
    (swap! (:faults w) assoc :writes 0)
    (is (= unavailable (reason (env/cancel! (:c w) (:a w) current))))
    (is (nil? (:cancel! @(:calls w))))
    (is (= [:state/provisioned] (states w)))))

(deftest ^{:stratum 1} test-a-release-the-ledger-cannot-record-deletes-nothing
  (let [w (outage-world)
        current (:environment/lease (acquire w))]
    (swap! (:faults w) assoc :writes 0)
    (is (= unavailable (reason (env/release! (:c w) (:a w) current))))
    (is (nil? (:deprovision! @(:calls w))))
    (is (= [:state/provisioned] (states w)))
    (testing "given the ledger back → the release is recorded and runs"
      (swap! (:faults w) assoc :writes nil)
      (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) current)))
      (is (= [:state/released] (states w))))))

(deftest ^{:stratum 1} test-controllers-sharing-a-ledger-share-its-leases
  (let [w (outage-world)
        other (second-controller w)
        req (fx/request w (:a w))
        acquired (fx/acquire w (:a w) req)]
    (is (= {:readiness/ready? true} (env/readiness other (:a w) (:environment/lease acquired))))
    (is (= acquired (env/acquire! other (:a w) (fx/provision-grant w (:a w)) req)))
    (is (= 1 (:provision! @(:calls w))))))

(deftest ^{:stratum 1} test-racing-controllers-provision-once
  (let [w (outage-world)
        req (fx/request w (:a w))
        grant (fx/provision-grant w (:a w))
        racers (mapv #(future (env/acquire! % (:a w) grant req)) [(:c w) (second-controller w)])
        results (mapv deref racers)
        environments (remove anomaly/anomaly? results)]
    (is (= 1 (:provision! @(:calls w))))
    (is (= 1 (count (distinct environments))))
    (is (every? #{:anomalies.agent-environment/acquire-in-flight} (map reason (filter anomaly/anomaly? results))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-a-refused-request-never-mutates-the-provider
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (fx/acquire w (:a w) req)
    (let [before (mutations w)
          changed (assoc req :environment/template-hash (second fx/template-hashes))]
      (testing "given a changed request for the same key → refused, no provider mutation"
        (is (= :anomalies.agent-environment/conflicting-request (reason (fx/acquire w (:a w) changed))))
        (is (= before (mutations w))))
      (testing "given a request naming another tenant → refused, no provider mutation"
        (is (= :anomalies.agent-environment/forged-tenant (reason (fx/acquire w (:a w) (fx/request w (:b w))))))
        (is (= before (mutations w)))))))
