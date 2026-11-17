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
(ns ai.miniforge.agent-environment.cleanup-test
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.fake :as fake]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger-faults :refer [outage-world]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} unavailable :anomalies.agent-environment/ledger-unavailable)

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} acquired [w] (fx/acquire w (:a w) (fx/request w (:a w))))

(defn- ^{:stratum 0} stored
  "The one entry `w`'s ledger holds."
  [w]
  (first (ledger/all-entries (:inner w))))

(defn- ^{:stratum 0} stages [entry] ((juxt :entry/state (comp :cleanup/applied? :entry/cleanup) (comp :cleanup/verified? :entry/cleanup)) entry))

(defn- ^{:stratum 0} still-there
  "An `observe` hook that keeps reporting `uid` and its generation, as a
   namespace still terminating after its delete was accepted would."
  [uid generation]
  (constantly {:observed/uid uid :observed/generation generation :observed/ready? false}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-a-release-records-its-intent-before-any-delete
  (let [w (outage-world)
        current (:environment/lease (acquired w))]
    (swap! (:faults w) assoc :before-write #(is (nil? (:deprovision! @(:calls w)))))
    (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) current)))
    (is (= [:state/released true true] (stages (stored w))))
    (is (= (:lease/environment-uid current) (get-in (stored w) [:entry/cleanup :cleanup/uid])))))

(deftest ^{:stratum 1} test-a-release-whose-outcome-the-ledger-misses-is-retried
  (let [w (outage-world)
        current (:environment/lease (acquired w))]
    (swap! (:faults w) assoc :writes 1)
    (is (= unavailable (reason (env/release! (:c w) (:a w) current))))
    (testing "given the deletes ran but their outcome was not recorded → the entry stays releasing"
      (is (empty? (fake/live-namespaces (:fake w))))
      (is (= [:state/releasing false false] (stages (stored w))))
      (is (= :anomalies.agent-environment/environment-releasing (reason (env/exec! (:c w) (:a w) current fx/probe-command)))))
    (testing "given the ledger back → a retried release deletes again and is verified"
      (swap! (:faults w) assoc :writes nil)
      (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) current)))
      (is (= [:state/released true true] (stages (stored w)))))))

(deftest ^{:stratum 1} test-an-unverified-delete-is-not-a-release
  (let [w (outage-world)
        current (:environment/lease (acquired w))]
    (swap! (:hooks w) assoc :observe (still-there (:lease/environment-uid current) (:lease/generation current)))
    (is (= {:call/outcome :outcome/uncertain} (env/release! (:c w) (:a w) current)))
    (is (= [:state/cleanup-uncertain true false] (stages (stored w))))
    (testing "given the UID gone at the next attempt → released"
      (swap! (:hooks w) dissoc :observe)
      (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) current)))
      (is (= :state/released (:entry/state (stored w)))))))

(deftest ^{:stratum 1} test-a-partial-provision-is-cleaned-up-under-an-intent
  (let [w (outage-world {:fake/fail-step :step/workload})]
    (is (= :anomalies.agent-environment/provisioning-failed (reason (acquired w))))
    (is (= [:state/released true true] (stages (stored w))))
    (testing "given the ledger cannot take the cleanup intent → nothing is deleted"
      (let [w (outage-world {:fake/fail-step :step/workload})]
        (swap! (:faults w) assoc :writes 1)
        (is (= unavailable (reason (acquired w))))
        (is (nil? (:deprovision! @(:calls w))))
        (is (= 1 (count (fake/live-namespaces (:fake w)))))
        (is (= :state/provisioning (:entry/state (stored w))))))))

(deftest ^{:stratum 1} test-acquire-waits-while-a-release-is-pending
  (let [w (outage-world)
        req (fx/request w (:a w))
        current (:environment/lease (fx/acquire w (:a w) req))]
    (swap! (:faults w) assoc :writes 1)
    (env/release! (:c w) (:a w) current)
    (swap! (:faults w) assoc :writes nil)
    (is (= :anomalies.agent-environment/environment-releasing (reason (fx/acquire w (:a w) req))))
    (is (= :state/releasing (:entry/state (ledger/entry (:inner w) (lease/request-key req)))))))
