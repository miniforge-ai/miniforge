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
  {:entry/state state
   :entry/generation generation
   :entry/request req
   :entry/environment {:environment/lease (lease/lease-for req uid generation)}})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-acquire-decision-per-ledger-state
  (testing "given no entry or a released one → a new generation"
    (is (= :new (:decision/kind (lease/acquire-decision nil (request)))))
    (is (= :new (:decision/kind (lease/acquire-decision (entry :state/released (request)) (request))))))
  (testing "given the identical request provisioned → duplicate"
    (is (= :duplicate (:decision/kind (lease/acquire-decision (entry :state/provisioned (request)) (request))))))
  (testing "given a different request for the same key → conflict"
    (is (= :anomalies.agent-environment/conflicting-request
           (:decision/reason (lease/acquire-decision (entry :state/provisioned (request))
                                                     (request :environment/template-hash "other"))))))
  (testing "given an in-flight or uncertain entry → refused with the state's reason"
    (is (= :anomalies.agent-environment/acquire-in-flight
           (:decision/reason (lease/acquire-decision (entry :state/provisioning (request)) (request)))))
    (is (= :anomalies.agent-environment/cleanup-uncertain
           (:decision/reason (lease/acquire-decision (entry :state/cleanup-uncertain (request)) (request)))))))

(deftest ^{:stratum 2} test-reserve-advances-generation-past-released
  (is (= 1 (:entry/generation (lease/reserve nil (request)))))
  (is (= 4 (:entry/generation (lease/reserve (entry :state/released (request) :generation 3) (request))))))

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

(deftest ^{:stratum 2} test-unleased-cleanup-is-retried-by-the-next-acquire
  (let [uncertain (assoc (entry :state/cleanup-uncertain (request)) :entry/environment nil)]
    (is (= :new (:decision/kind (lease/acquire-decision uncertain (request)))))
    (is (= :anomalies.agent-environment/conflicting-request
           (:decision/reason (lease/acquire-decision uncertain (request :environment/template-hash "other")))))
    (is (= :anomalies.agent-environment/unknown-lease
           (lease/lease-refusal uncertain :environment/release nil now)))
    (is (= :anomalies.agent-environment/cleanup-uncertain
           (:decision/reason (lease/acquire-decision (entry :state/cleanup-uncertain (request)) (request)))))))

(deftest ^{:stratum 2} test-names-and-hashes-reject-a-trailing-newline
  (is (not (m/validate schema/NamespaceName (str (lease/namespace-name (request)) "\n"))))
  (is (not (m/validate schema/Sha256Hex (str (apply str (repeat 64 "a")) "\n")))))
