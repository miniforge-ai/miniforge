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
(ns ai.miniforge.agent-environment.lifecycle-test
  (:require
   [ai.miniforge.agent-environment.conformance.fixtures :as fx]
   [ai.miniforge.agent-environment.fake :as fake]
   [ai.miniforge.agent-environment.fake.world :as world]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} world
  ([] (world {}))
  ([opts] (fx/world (env/fake-provider opts) :isolation/dedicated-kernel)))

(defn- ^{:stratum 0} acquire [w] (fx/acquire w (:a w) (fx/request w (:a w))))

(defn- ^{:stratum 0} live [w] (fake/live-namespaces (:controller/provider (:c w))))

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

(defn- ^{:stratum 0} lease-of [environment] (:environment/lease environment))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-duplicate-acquire-returns-one-environment
  (let [w (world)
        req (fx/request w (:a w))
        first-env (fx/acquire w (:a w) req)]
    (is (= first-env (fx/acquire w (:a w) req)))
    (is (= [:outcome/applied :outcome/duplicate] (fx/outcomes w :environment/acquire)))
    (is (= 1 (count (live w))))))

(deftest ^{:stratum 1} test-conflicting-request-is-refused-and-leaves-the-environment
  (let [w (world)
        req (fx/request w (:a w))
        env-value (fx/acquire w (:a w) req)
        changed (assoc req :environment/template-hash (second fx/template-hashes))]
    (is (= :anomalies.agent-environment/conflicting-request (reason (fx/acquire w (:a w) changed))))
    (is (= {:readiness/ready? true} (env/readiness (:c w) (:a w) (lease-of env-value))))))

(deftest ^{:stratum 1} test-forged-tenant-is-refused
  (let [w (world)]
    (testing "given a request naming another tenant → refused before provisioning"
      (is (= :anomalies.agent-environment/forged-tenant (reason (fx/acquire w (:a w) (fx/request w (:b w))))))
      (is (empty? (live w))))
    (testing "given another tenant's lease → refused"
      (is (= :anomalies.agent-environment/forged-tenant
             (reason (env/readiness (:c w) (:a w) (lease-of (fx/acquire w (:b w) (fx/request w (:b w))))))))
      (is (= :outcome/refused (:receipt/outcome (last @(:receipts w))))))))

(deftest ^{:stratum 1} test-acquire-without-a-grant-is-refused
  (let [w (world)]
    (is (= :anomalies.agent-environment/grant-absent
           (reason (env/acquire! (:c w) (:a w) nil (fx/request w (:a w))))))
    (is (= [:outcome/refused] (fx/outcomes w :environment/acquire)))))

(deftest ^{:stratum 1} test-old-lease-is-stale-after-release-and-reacquire
  (let [w (world)
        req (fx/request w (:a w))
        old-lease (lease-of (fx/acquire w (:a w) req))
        _ (env/release! (:c w) (:a w) old-lease)
        new-lease (lease-of (fx/acquire w (:a w) req))]
    (is (= [1 2] (mapv :lease/generation [old-lease new-lease])))
    (is (= :anomalies.agent-environment/stale-lease (reason (env/readiness (:c w) (:a w) old-lease))))
    (is (= :anomalies.agent-environment/stale-lease (reason (env/release! (:c w) (:a w) old-lease))))
    (is (= {:readiness/ready? true} (env/readiness (:c w) (:a w) new-lease)))))

(deftest ^{:stratum 1} test-partial-provisioning-is-receipted-and-cleaned-up
  (let [w (world {:fake/fail-step :step/workload})]
    (is (= :anomalies.agent-environment/provisioning-failed (reason (acquire w))))
    (is (= [:outcome/partial] (fx/outcomes w :environment/acquire)))
    (is (= [:outcome/applied] (fx/outcomes w :environment/release)))
    (is (empty? (live w)))))

(deftest ^{:stratum 1} test-unconfirmed-delete-stays-visible-until-retried
  (let [w (world {:fake/uncertain-deletes? true})
        lease (lease-of (acquire w))]
    (testing "given a delete the provider did not confirm → uncertain, not released"
      (is (= :outcome/uncertain (:call/outcome (env/release! (:c w) (:a w) lease))))
      (is (= :anomalies.agent-environment/cleanup-uncertain
             (reason (fx/acquire w (:a w) (fx/request w (:a w) (:lease/task-id lease) (first fx/template-hashes)))))))
    (testing "given the same lease → release may be retried"
      (is (= :outcome/uncertain (:call/outcome (env/release! (:c w) (:a w) lease)))))))

(deftest ^{:stratum 1} test-recreated-namespace-is-not-the-leased-environment
  (let [w (world)
        env-value (acquire w)]
    (fake/replace-uid! (:controller/provider (:c w)) (:environment/namespace env-value))
    (is (= :anomalies.agent-environment/uid-mismatch (reason (env/readiness (:c w) (:a w) (lease-of env-value)))))
    (is (= :outcome/refused (:receipt/outcome (last @(:receipts w)))))))

(deftest ^{:stratum 1} test-preflight-refuses-isolation-the-provider-lacks
  (let [w (world {:fake/capabilities (assoc world/default-capabilities
                                            :capabilities/isolation #{:isolation/shared-kernel})})
        req (fx/request w (:a w))]
    (is (= [:anomalies.agent-environment/isolation-unavailable]
           (:preflight/failures (env/preflight (:c w) (:a w) req))))
    (is (= :anomalies.agent-environment/preflight-failed (reason (fx/acquire w (:a w) req))))
    (is (empty? (live w)))))

(deftest ^{:stratum 1} test-invalid-input-is-refused-without-a-receipt
  (let [w (world)]
    (is (= :anomalies.agent-environment/invalid-input
           (reason (env/acquire! (:c w) (:a w) nil (dissoc (fx/request w (:a w)) :environment/policy-hash)))))
    (is (= :anomalies.agent-environment/invalid-input (reason (env/create-controller {:provider :not-a-provider}))))
    (is (empty? @(:receipts w)))))

(deftest ^{:stratum 1} test-release-of-an-already-deleted-namespace-is-confirmed
  (let [w (world)
        env-value (acquire w)]
    (fake/forget-namespace! (:controller/provider (:c w)) (:environment/namespace env-value))
    (is (= {:call/outcome :outcome/applied} (env/release! (:c w) (:a w) (lease-of env-value))))))

(deftest ^{:stratum 1} test-a-provider-that-omits-the-uid-still-yields-a-lease
  (testing "given a complete provision with no UID reported → the UID is observed"
    (let [w (world {:fake/omit-uid? true})]
      (is (string? (:lease/environment-uid (lease-of (acquire w)))))))
  (testing "given a partial one with unconfirmed cleanup → its lease lets release retry"
    (let [w (world {:fake/omit-uid? true :fake/fail-step :step/workload :fake/uncertain-deletes? true})
          failed (acquire w)
          recovered (get-in failed [:anomaly/data :environment/lease])]
      (is (= :anomalies.agent-environment/provisioning-failed (reason failed)))
      (is (= :outcome/uncertain (:call/outcome (env/release! (:c w) (:a w) recovered))))))
  (testing "given the namespace recreated by someone else before it is observed → not adopted, not deleted"
    (let [w (world {:fake/omit-uid? true :fake/recreated-after-provision? true})
          failed (acquire w)]
      (is (= :anomalies.agent-environment/provisioning-failed (reason failed)))
      (is (nil? (get-in failed [:anomaly/data :environment/lease])))
      (is (= 1 (count (live w))))
      (is (empty? (fx/outcomes w :environment/release)))))
  (testing "given steps reported only as uncertain → the UID is still recovered and the leftover removed"
    (let [w (world {:fake/omit-uid? true :fake/uncertain-steps? true})
          failed (acquire w)]
      (is (= :anomalies.agent-environment/provisioning-failed (reason failed)))
      (is (some? (get-in failed [:anomaly/data :environment/lease])))
      (is (empty? (live w)))
      (is (= [:outcome/applied] (fx/outcomes w :environment/release))))))
