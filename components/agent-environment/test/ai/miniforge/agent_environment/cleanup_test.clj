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
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger-faults :as faults :refer [outage-world]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} unavailable :anomalies.agent-environment/ledger-unavailable)

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(def ^{:stratum 0} failed-delete
  "A provider's answer to a delete it reports as failed."
  {:deprovision/steps [{:step/name :step/namespace-deleted :step/outcome :outcome/failed}]})

(defn- ^{:stratum 0} acquired [w] (fx/acquire w (:a w) (fx/request w (:a w))))

(defn- ^{:stratum 0} frozen
  "A controller over `w`'s ledger whose clock never moves, so two attempts share one instant."
  [w]
  (faults/controller-over w (get-in w [:c :controller/ledger]) {:clock (constantly (Instant/now))}))

(defn- ^{:stratum 0} target-of
  [req lease]
  {:target/namespace (lease/namespace-name req) :target/uid (:lease/environment-uid lease) :target/generation (:lease/generation lease)})

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

(defn- ^{:stratum 0} during-delete
  "A `deprovision!` hook that runs `rival!` once, before the provider
   deletes; it returns nil, so the provider still answers."
  [w rival!]
  (fn []
    (swap! (:hooks w) dissoc :deprovision!)
    (rival!)
    nil))

(defn- ^{:stratum 0} unrecorded-release!
  "Release `lease` with its outcome write failing, as an overlapping
   attempt that deleted and then lost its ledger would."
  [w lease]
  (swap! (:faults w) assoc :writes 1)
  (env/release! (:c w) (:a w) lease)
  (swap! (:faults w) assoc :writes nil))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failed-after-deleting
  "A `deprovision!` hook: the fake deletes `target`, but the provider reports
   the delete failed, as one that left other resources behind would."
  [w target]
  (fn []
    (protocol/deprovision! (:fake w) target)
    failed-delete))

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
    (testing "given the ledger cannot take the cleanup intent → nothing is deleted, and no lease is offered"
      (let [w (outage-world {:fake/fail-step :step/workload})]
        (swap! (:faults w) assoc :writes 1)
        (let [refused (acquired w)]
          (is (= unavailable (reason refused)))
          (is (nil? (get-in refused [:anomaly/data :environment/lease])))
          (is (nil? (:deprovision! @(:calls w))))
          (is (= 1 (count (fake/live-namespaces (:fake w)))))
          (is (= :state/provisioning (:entry/state (stored w)))))))))

(deftest ^{:stratum 1} test-acquire-waits-while-a-release-is-pending
  (let [w (outage-world)
        req (fx/request w (:a w))
        current (:environment/lease (fx/acquire w (:a w) req))]
    (swap! (:faults w) assoc :writes 1)
    (env/release! (:c w) (:a w) current)
    (swap! (:faults w) assoc :writes nil)
    (is (= :anomalies.agent-environment/environment-releasing (reason (fx/acquire w (:a w) req))))
    (is (= :state/releasing (:entry/state (ledger/entry (:inner w) (lease/request-key req)))))))

(deftest ^{:stratum 1} test-each-release-attempt-holds-its-own-intent
  (let [w (outage-world)
        current (:environment/lease (acquired w))
        c (frozen w)]
    (swap! (:faults w) assoc :writes 1)
    (env/release! c (:a w) current)
    (let [first-attempt (get-in (stored w) [:entry/cleanup :cleanup/attempt])]
      (swap! (:faults w) assoc :writes nil)
      (is (= {:call/outcome :outcome/applied} (env/release! c (:a w) current)))
      (testing "given a retry at the same instant → it writes and settles its own intent"
        (is (some? first-attempt))
        (is (not= first-attempt (get-in (stored w) [:entry/cleanup :cleanup/attempt])))))))

(deftest ^{:stratum 1} test-an-unrecorded-partial-cleanup-keeps-its-lease
  (let [w (outage-world {:fake/fail-step :step/workload})]
    (swap! (:faults w) assoc :writes 2)
    (let [refused (acquired w)
          kept (get-in refused [:anomaly/data :environment/lease])]
      (is (= unavailable (reason refused)))
      (is (= :state/releasing (:entry/state (stored w))))
      (testing "given the ledger back → releasing the kept lease finishes the cleanup"
        (swap! (:faults w) assoc :writes nil)
        (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) kept)))
        (is (= :state/released (:entry/state (stored w))))))))

(deftest ^{:stratum 1} test-a-release-another-attempt-took-over-is-not-reported-done
  (let [w (outage-world)
        current (:environment/lease (acquired w))]
    (swap! (:hooks w) assoc :deprovision! (during-delete w #(unrecorded-release! w current)))
    (is (= :anomalies.agent-environment/environment-releasing (reason (env/release! (:c w) (:a w) current))))
    (is (= :state/releasing (:entry/state (stored w))))
    (testing "given a retry → released"
      (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) current)))
      (is (= :state/released (:entry/state (stored w)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-a-delete-reported-failed-is-not-a-release
  (let [w (outage-world)
        req (fx/request w (:a w))
        current (:environment/lease (fx/acquire w (:a w) req))]
    (swap! (:hooks w) assoc :deprovision! (failed-after-deleting w (target-of req current)))
    (is (= {:call/outcome :outcome/partial} (env/release! (:c w) (:a w) current)))
    (testing "given the UID gone but the delete reported failed → kept to retry"
      (is (= [:state/cleanup-uncertain false true] (stages (stored w)))))
    (testing "given a retry whose delete applies → released"
      (swap! (:hooks w) dissoc :deprovision!)
      (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) current)))
      (is (= :state/released (:entry/state (stored w)))))))
