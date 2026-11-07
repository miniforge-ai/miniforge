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
(ns ai.miniforge.agent-environment.controller-test
  (:require
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-11-06T00:00:00Z"))

(def ^{:stratum 0} uid "uid-1")

(def ^{:stratum 0} other-generation
  "A generation the ledger never issued for this key."
  7)

(def ^{:stratum 0} request
  {:environment/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :environment/run-id #uuid "00000000-0000-0000-0000-00000000000b"
   :environment/task-id #uuid "00000000-0000-0000-0000-00000000000c"
   :environment/template-hash "t"})

(defn- ^{:stratum 0} collect! [receipts receipt] (swap! receipts conj receipt))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} subject
  {:receipt/tenant-id (:environment/tenant-id request)
   :receipt/principal-id #uuid "00000000-0000-0000-0000-0000000000a1"
   :receipt/run-id (:environment/run-id request)
   :receipt/task-id (:environment/task-id request)
   :receipt/environment-uid nil
   :receipt/lease-generation nil})

(defn- ^{:stratum 1} fresh
  []
  (let [receipts (atom [])
        c (controller/create {:provider nil :clock (constantly now) :emit! (partial collect! receipts)})]
    {:c c :receipts receipts}))

(defn- ^{:stratum 1} provisioned!
  "Claim `request` and settle it provisioned; returns its lease."
  [c]
  (let [generation (:decision/generation (controller/claim! c request))
        current (lease/lease-for request uid generation)
        env {:environment/request request :environment/uid uid :environment/lease current}]
    (controller/settle-provision! c request generation :state/provisioned env)
    current))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-claim-reserves-once
  (let [{:keys [c]} (fresh)]
    (is (= {:decision/kind :new :decision/generation 1} (controller/claim! c request)))
    (testing "given a claim already in flight → refused, the reservation kept"
      (is (= :anomalies.agent-environment/acquire-in-flight (:decision/reason (controller/claim! c request))))
      (is (= :state/provisioning (:entry/state (controller/entry c (lease/request-key request))))))))

(deftest ^{:stratum 2} test-settle-moves-only-the-current-lease
  (let [{:keys [c]} (fresh)
        current (provisioned! c)
        k (lease/request-key request)]
    (controller/settle! c (assoc current :lease/generation other-generation) #(assoc % :entry/state :state/released))
    (is (= :state/provisioned (:entry/state (controller/entry c k))))
    (controller/settle! c current #(assoc % :entry/state :state/cancelled))
    (is (= :state/cancelled (:entry/state (controller/entry c k))))))

(deftest ^{:stratum 2} test-refusals-and-results-are-receipted-before-returning
  (let [{:keys [c receipts]} (fresh)
        refused (controller/refuse! c subject :environment/acquire :anomalies.agent-environment/forged-tenant [])]
    (is (= (:receipt/id (last @receipts)) (get-in refused [:anomaly/data :receipt/id])))
    (is (= :not-found (:anomaly/type (controller/finish! c subject :environment/exec (anomaly/anomaly :not-found "x" {})))))
    (is (= [:outcome/refused :outcome/failed] (mapv :receipt/outcome @receipts)))
    (is (= :not-found (:receipt/reason (last @receipts))))
    (is (= 0 (controller/finish! c subject :environment/exec {:call/value 0 :call/steps []})))
    (is (= :outcome/applied (:receipt/outcome (last @receipts))))))
