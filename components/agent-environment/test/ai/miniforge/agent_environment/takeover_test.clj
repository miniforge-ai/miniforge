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
(ns ai.miniforge.agent-environment.takeover-test
  "One controller taking over another's acquisition: what a late owner may
   still do, what a reconcile adopts or deletes, and when provisioning
   must not start."
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.controller :as controller]
   [ai.miniforge.agent-environment.fake :as fake]
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.agent-environment.interface.protocol :as protocol]
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.ledger :as ledger]
   [ai.miniforge.agent-environment.ledger-faults :as faults :refer [outage-world]]
   [ai.miniforge.agent-environment.lifecycle.support :as support]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} first-generation
  "The generation a key's first reservation takes."
  1)

(def ^{:stratum 0} unconfirmed-provision
  "A provider's answer to a provision whose last step it could not confirm."
  {:provision/steps [{:step/name :step/workload :step/outcome :outcome/uncertain}]})

(def ^{:stratum 0} unconfirmed-delete
  "A provider's answer to a delete it accepted but has not confirmed."
  {:deprovision/steps [{:step/name :step/namespace-deleted :step/outcome :outcome/uncertain}]})

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} target-of
  [req generation]
  {:target/namespace (lease/namespace-name req) :target/uid nil :target/generation generation})

(defn- ^{:stratum 0} stored [w req] (ledger/entry (:inner w) (lease/request-key req)))

(defn- ^{:stratum 0} release-outcomes [w] (fx/outcomes w :environment/release))

(defn- ^{:stratum 0} live-uid
  "The UID of the one namespace the fake holds."
  [w]
  (get-in @(:state (:fake w)) [(first (fake/live-namespaces (:fake w))) :uid]))

(defn- ^{:stratum 0} before-dispatch
  "Run `rival!` after the owner's claim is written and before its dispatch
   re-reads the reservation."
  [w rival!]
  (swap! (:faults w) assoc :before-write #(swap! (:faults w) assoc :before-read rival!)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} rival-adopted!
  "Write the reservation as a rival that adopted the environment the fake
   just made, leaving it in `state`, would."
  [w req state]
  (let [k (lease/request-key req)
        adopted (support/environment req (target-of req first-generation) (live-uid w))
        {:ledger/keys [entry revision]} (store/read-entry (:inner w) k)]
    (store/write-entry! (:inner w) k revision (assoc entry :entry/state state :entry/environment adopted :entry/claim nil))))

(defn- ^{:stratum 1} unconfirmed-after
  "A `provision!` hook: the fake provisions, `rival!` runs, and the owner
   hears back only an unconfirmed provision."
  [w req rival!]
  (fn []
    (protocol/provision! (:fake w) (target-of req first-generation) req)
    (rival!)
    unconfirmed-provision))

(deftest ^{:stratum 1} test-a-partial-environment-is-deleted-not-adopted
  (let [w (outage-world {:fake/fail-step :step/workload})
        req (fx/request w (:a w))]
    (controller/claim! (:c w) req)
    (protocol/provision! (:fake w) (target-of req first-generation) req)
    (let [uid (live-uid w)]
      (is (= :anomalies.agent-environment/provisioning-failed (reason (faults/reacquire w (faults/late-controller w) req))))
      (testing "given the crashed owner's namespace without its workload → deleted before the next generation"
        (is (empty? (fake/live-namespaces (:fake w))))
        (is (= [:outcome/applied :outcome/applied] (release-outcomes w)))
        (is (= [:outcome/applied] (fx/outcomes w :environment/reconcile))))
      (testing "given the leftover's UID known → its reconcile and release receipts name it"
        (is (= [uid] (mapv :receipt/environment-uid (faults/receipts-of w :environment/reconcile))))
        (is (= uid (:receipt/environment-uid (first (faults/receipts-of w :environment/release)))))))))

(deftest ^{:stratum 1} test-a-cleanup-left-without-a-uid-is-reconciled
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (controller/claim! (:c w) req)
    (protocol/provision! (:fake w) (target-of req first-generation) req)
    (controller/settle-provision! (:c w) req first-generation :state/cleanup-uncertain nil)
    (let [replaced (fx/acquire w (:a w) req)]
      (is (= 2 (get-in replaced [:environment/lease :lease/generation])))
      (is (= #{(:environment/namespace replaced)} (fake/live-namespaces (:fake w))))
      (is (= [:outcome/applied] (release-outcomes w))))))

(deftest ^{:stratum 1} test-a-late-owners-cleanup-stays-tracked-until-verified
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (swap! (:hooks w) assoc :provision! (faults/at-record w #(faults/abandon! w req)) :deprovision! (constantly unconfirmed-delete))
    (is (= :anomalies.agent-environment/claim-lost (reason (fx/acquire w (:a w) req))))
    (testing "given the late owner's delete unconfirmed → the ledger keeps it to retry"
      (is (= :state/cleanup-uncertain (:entry/state (stored w req))))
      (is (some? (:entry/environment (stored w req)))))
    (testing "given the next acquire → the leftover is deleted and a new generation provisioned"
      (swap! (:hooks w) dissoc :provision! :deprovision!)
      (is (= 2 (get-in (fx/acquire w (:a w) req) [:environment/lease :lease/generation])))
      (is (= 1 (count (fake/live-namespaces (:fake w))))))))

(deftest ^{:stratum 1} test-a-reservation-reconciled-before-dispatch-is-not-provisioned
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (before-dispatch w #(faults/reacquire w (faults/late-controller w) req))
    (is (= :anomalies.agent-environment/claim-lost (reason (fx/acquire w (:a w) req))))
    (testing "given a rival that reconciled and provisioned the next generation first → only the rival provisions"
      (is (= 1 (:provision! @(:calls w))))
      (is (= [:state/provisioned 2] ((juxt :entry/state :entry/generation) (stored w req)))))))

(deftest ^{:stratum 1} test-a-late-owners-partial-provision-is-deleted
  (let [w (outage-world {:fake/fail-step :step/workload})
        req (fx/request w (:a w))]
    (swap! (:hooks w) assoc :provision! (faults/at-record w #(faults/abandon! w req)))
    (is (= :anomalies.agent-environment/provisioning-failed (reason (fx/acquire w (:a w) req))))
    (testing "given a rival abandoned the reservation before the owner's cleanup → the owner still deletes"
      (is (empty? (fake/live-namespaces (:fake w))))
      (is (= :state/released (:entry/state (stored w req)))))))

(deftest ^{:stratum 1} test-an-interrupted-reconcile-is-resumed-once-its-claim-expires
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (controller/claim! (:c w) req)
    (protocol/provision! (:fake w) (target-of req first-generation) req)
    (controller/settle-provision! (:c w) req first-generation :state/cleanup-uncertain nil)
    (swap! (:faults w) assoc :writes 1)
    (is (= :anomalies.agent-environment/ledger-unavailable (reason (faults/reacquire w (faults/late-controller w) req))))
    (swap! (:faults w) assoc :writes nil)
    (testing "given the reconcile's cleanup left pending → fenced while its claim lasts"
      (is (= :state/releasing (:entry/state (stored w req))))
      (is (= :anomalies.agent-environment/environment-releasing (reason (faults/reacquire w (faults/late-controller w) req)))))
    (testing "given its claim expired → the next acquire finishes the cleanup and provisions"
      (is (= 2 (get-in (faults/reacquire w (faults/restarted w faults/much-later) req) [:environment/lease :lease/generation])))
      (is (= 1 (count (fake/live-namespaces (:fake w))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-a-late-owner-gets-the-adopted-environments-refusal
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (swap! (:hooks w) assoc :provision! (faults/at-record w #(rival-adopted! w req :state/cancelled)))
    (is (= :anomalies.agent-environment/environment-cancelled (reason (fx/acquire w (:a w) req))))
    (is (nil? (:deprovision! @(:calls w))))
    (is (= 1 (count (fake/live-namespaces (:fake w)))))))

(deftest ^{:stratum 2} test-a-failed-provision-never-deletes-what-a-rival-adopted
  (let [w (outage-world)
        req (fx/request w (:a w))]
    (swap! (:hooks w) assoc :provision! (unconfirmed-after w req #(rival-adopted! w req :state/provisioned)))
    (is (= :anomalies.agent-environment/provisioning-failed (reason (fx/acquire w (:a w) req))))
    (testing "given the rival adopted the environment first → the owner's cleanup intent is not taken"
      (is (nil? (:deprovision! @(:calls w))))
      (is (= 1 (count (fake/live-namespaces (:fake w)))))
      (is (= :state/provisioned (:entry/state (stored w req)))))))
