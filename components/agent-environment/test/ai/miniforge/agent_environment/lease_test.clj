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
(ns ai.miniforge.agent-environment.lease-test
  (:require
   [ai.miniforge.agent-environment.lease :as lease]
   [ai.miniforge.agent-environment.schema :as schema]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} uid "uid-1")

(def ^{:stratum 0} now (Instant/parse "2026-11-06T00:00:00Z"))

(def ^{:stratum 0} expires-at
  "An hour after `now`: inside it the environment is live, past it expired."
  (Instant/parse "2026-11-06T01:00:00Z"))

(def ^{:stratum 0} controller-id
  "The controller these entries' claims name."
  #uuid "00000000-0000-0000-0000-0000000000c1")

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request
  [& {:as overrides}]
  (merge {:environment/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
          :environment/run-id #uuid "00000000-0000-0000-0000-00000000000b"
          :environment/task-id #uuid "00000000-0000-0000-0000-00000000000c"
          :environment/template-hash "t"
          :environment/expires-at expires-at}
         overrides))

(defn- ^{:stratum 1} entry
  [state req & {:keys [generation] :or {generation 1}}]
  (let [digest (lease/request-digest req)
        current (lease/lease-for req uid generation)
        claim (lease/claim controller-id expires-at)]
    {:entry/state state
     :entry/generation generation
     :entry/request req
     :entry/request-digest digest
     :entry/claim claim
     :entry/environment {:environment/lease current}}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-acquire-decision-per-ledger-state
  (testing "given no entry or a released one → a new generation"
    (is (= :new (:decision/kind (lease/acquire-decision nil (request) now))))
    (is (= :new (:decision/kind (lease/acquire-decision (entry :state/released (request)) (request) now)))))
  (testing "given the identical request provisioned → duplicate"
    (is (= :duplicate (:decision/kind (lease/acquire-decision (entry :state/provisioned (request)) (request) now)))))
  (testing "given a different request for the same key → conflict"
    (is (= :anomalies.agent-environment/conflicting-request
           (:decision/reason (lease/acquire-decision (entry :state/provisioned (request))
                                                     (request :environment/template-hash "other")
                                                     now)))))
  (testing "given an in-flight entry → refused; an uncertain cleanup → reconciled"
    (is (= :anomalies.agent-environment/acquire-in-flight
           (:decision/reason (lease/acquire-decision (entry :state/provisioning (request)) (request) now))))
    (is (= :reconcile (:decision/kind (lease/acquire-decision (entry :state/cleanup-uncertain (request)) (request) now))))))

(deftest ^{:stratum 2} test-reserve-advances-generation-past-released
  (let [claim (lease/claim controller-id expires-at)]
    (is (= 1 (:entry/generation (lease/reserve nil (request) claim))))
    (is (= 4 (:entry/generation (lease/reserve (entry :state/released (request) :generation 3) (request) claim))))))

(deftest ^{:stratum 2} test-requests-compare-by-digest
  (testing "given the same moment as a Date → the same request, a duplicate"
    (let [as-date (request :environment/expires-at (java.util.Date/from expires-at))
          decision (lease/acquire-decision (entry :state/provisioned (request)) as-date now)]
      (is (= :duplicate (:decision/kind decision))))))

(deftest ^{:stratum 2} test-a-claim-is-held-by-its-controller-for-its-generation
  (let [held (entry :state/provisioning (request))]
    (testing "given the same generation, controller and a live claim → held"
      (is (lease/claim-held? held controller-id 1 now)))
    (testing "given another generation, another controller, the claim's end, or no claim → not held"
      (is (not (lease/claim-held? held controller-id 2 now)))
      (is (not (lease/claim-held? held (random-uuid) 1 now)))
      (is (not (lease/claim-held? held controller-id 1 expires-at)))
      (is (not (lease/claim-held? (lease/reconciled held nil) controller-id 1 now))))
    (testing "given a cleanup that kept the claim → not held"
      (is (not (lease/claim-held? (assoc held :entry/state :state/releasing) controller-id 1 now))))))

(deftest ^{:stratum 2} test-an-expired-claim-is-reconciled
  (let [provisioning (entry :state/provisioning (request))]
    (testing "given the claim's end has passed → reconcile, not in flight"
      (is (= :reconcile (:decision/kind (lease/acquire-decision provisioning (request) expires-at)))))
    (testing "given a pending cleanup → reconciled past its claim's end, never when a lease holder owns it"
      (let [pending (entry :state/releasing (request))]
        (is (= :reconcile (:decision/kind (lease/acquire-decision pending (request) expires-at))))
        (is (= :anomalies.agent-environment/environment-releasing (:decision/reason (lease/acquire-decision pending (request) now))))
        (is (= :refuse (:decision/kind (lease/acquire-decision (assoc pending :entry/claim nil) (request) expires-at))))))
    (testing "given a provisioned entry past the claim's end → still a duplicate"
      (is (= :duplicate (:decision/kind (lease/acquire-decision (entry :state/provisioned (request)) (request) expires-at)))))
    (testing "given an environment → adopted; none → the reservation released"
      (is (= [:state/provisioned nil] ((juxt :entry/state :entry/claim) (lease/reconciled provisioning {:environment/uid uid}))))
      (is (= :state/released (:entry/state (lease/reconciled provisioning nil)))))))

(deftest ^{:stratum 2} test-lease-refusal
  (let [current (lease/lease-for (request) uid 2)
        live (entry :state/provisioned (request) :generation 2)]
    (testing "given the current lease in an admitting state → admitted"
      (is (nil? (lease/lease-refusal live :environment/exec current now))))
    (testing "given an older generation → stale"
      (is (= :anomalies.agent-environment/stale-lease
             (lease/lease-refusal live :environment/exec (assoc current :lease/generation 1) now))))
    (testing "given a cancelled environment → exec refused, release admitted"
      (is (= :anomalies.agent-environment/environment-cancelled
             (lease/lease-refusal (assoc live :entry/state :state/cancelled) :environment/exec current now)))
      (is (nil? (lease/lease-refusal (assoc live :entry/state :state/cancelled) :environment/release current now))))
    (testing "given an expired environment → work refused, cleanup admitted"
      (is (= :anomalies.agent-environment/environment-expired
             (lease/lease-refusal live :environment/exec current expires-at)))
      (is (nil? (lease/lease-refusal live :environment/release current expires-at))))
    (testing "given an operation no state admits → refused, never admitted by omission"
      (is (= :anomalies.agent-environment/operation-not-admitted
             (lease/lease-refusal live :environment/acquire current now))))
    (testing "given no entry → unknown lease"
      (is (= :anomalies.agent-environment/unknown-lease (lease/lease-refusal nil :environment/exec current now))))))

(deftest ^{:stratum 2} test-namespace-name-is-stable-and-a-dns-label
  (let [name (lease/namespace-name (request))]
    (is (= name (lease/namespace-name (request :environment/template-hash "other"))))
    (is (not= name (lease/namespace-name (request :environment/task-id (random-uuid)))))
    (is (m/validate schema/NamespaceName name))))

(deftest ^{:stratum 2} test-expiry-keeps-sub-millisecond-precision
  (let [deadline (Instant/parse "2026-11-06T01:00:00.000900Z")
        req (request :environment/expires-at deadline)]
    (is (not (lease/expired? req (Instant/parse "2026-11-06T01:00:00.000100Z"))))
    (is (lease/expired? req deadline))
    (testing "given any inst? expiry, java.sql.Date included → a decision, not an exception"
      (is (lease/expired? (request :environment/expires-at (java.sql.Date. 0)) deadline)))))

(deftest ^{:stratum 2} test-a-cleanup-with-no-uid-is-reconciled-not-released-by-lease
  (let [uncertain (assoc (entry :state/cleanup-uncertain (request)) :entry/environment nil)]
    (is (= :reconcile (:decision/kind (lease/acquire-decision uncertain (request) now))))
    (is (= :anomalies.agent-environment/conflicting-request
           (:decision/reason (lease/acquire-decision uncertain (request :environment/template-hash "other") now))))
    (is (= :anomalies.agent-environment/unknown-lease
           (lease/lease-refusal uncertain :environment/release nil now)))))

(deftest ^{:stratum 2} test-names-and-hashes-reject-a-trailing-newline
  (is (not (m/validate schema/NamespaceName (str (lease/namespace-name (request)) "\n"))))
  (is (not (m/validate schema/Sha256Hex (str (apply str (repeat 64 "a")) "\n")))))

(deftest ^{:stratum 2} test-digests-ignore-the-callers-printer-settings
  (let [plain [(lease/request-digest (request)) (lease/namespace-name (request))]
        other (request :environment/template-hash "other")]
    (binding [*print-level* 0 *print-length* 1]
      (testing "given printer bindings that truncate → the same digest and namespace"
        (is (= plain [(lease/request-digest (request)) (lease/namespace-name (request))])))
      (testing "given a different request under them → a different digest"
        (is (not= (lease/request-digest (request)) (lease/request-digest other)))))))
