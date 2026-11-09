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
(ns ai.miniforge.agent-environment.admission-test
  (:require
   [ai.miniforge.agent-environment.admission :as admission]
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-11-06T00:00:00Z"))

(def ^{:stratum 0} ttl-seconds 600)

(def ^{:stratum 0} acting
  {:acting/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :acting/principal-id #uuid "00000000-0000-0000-0000-0000000000a1"
   :acting/established-at "2026-11-06T00:00:00Z"})

(def ^{:stratum 0} capabilities
  {:capabilities/provider :provider/test
   :capabilities/isolation #{:isolation/shared-kernel}
   :capabilities/max-limits {:limits/cpu-millicores 1000 :limits/memory-mib 1024 :limits/ephemeral-storage-mib 2048}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} request
  [& {:as overrides}]
  (merge {:environment/tenant-id (:acting/tenant-id acting)
          :environment/principal-id (:acting/principal-id acting)
          :environment/run-id #uuid "00000000-0000-0000-0000-00000000000b"
          :environment/task-id #uuid "00000000-0000-0000-0000-00000000000c"
          :environment/isolation :isolation/dedicated-kernel
          :environment/limits {:limits/cpu-millicores 500 :limits/memory-mib 512 :limits/ephemeral-storage-mib 1024}}
         overrides))

(defn- ^{:stratum 1} provision-grant
  [& {:keys [effect-class principal scope expires-at]
      :or {effect-class :effect/provision
           principal (str (:acting/principal-id acting))
           scope (select-keys (request) [:environment/tenant-id :environment/run-id])
           expires-at (.plusSeconds now ttl-seconds)}}]
  (grant/issue {:principal principal :effect-class effect-class :scope scope :expires-at expires-at} now))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-identity-must-be-the-acting-context
  (is (nil? (admission/identity-refusal acting (request))))
  (is (= :anomalies.agent-environment/forged-tenant
         (admission/identity-refusal acting (request :environment/tenant-id (random-uuid)))))
  (is (= :anomalies.agent-environment/forged-principal
         (admission/identity-refusal acting (request :environment/principal-id (random-uuid))))))

(deftest ^{:stratum 2} test-grant-must-authorize-this-provision
  (testing "given a live provision grant for the run → admitted"
    (is (nil? (admission/grant-refusal (provision-grant) acting (request) now))))
  (testing "given each way the grant can fail → its own reason"
    (is (= :anomalies.agent-environment/grant-absent (admission/grant-refusal nil acting (request) now)))
    (is (= :anomalies.agent-environment/grant-wrong-effect
           (admission/grant-refusal (provision-grant :effect-class :effect/push) acting (request) now)))
    (is (= :anomalies.agent-environment/grant-wrong-principal
           (admission/grant-refusal (provision-grant :principal "someone-else") acting (request) now)))
    (is (= :anomalies.agent-environment/grant-scope-mismatch
           (admission/grant-refusal (provision-grant :scope {:environment/run-id (random-uuid)}) acting (request) now)))
    (is (= :anomalies.agent-environment/grant-inactive
           (admission/grant-refusal (provision-grant) acting (request) (.plusSeconds now (inc ttl-seconds)))))))

(deftest ^{:stratum 2} test-expired-request-is-refused
  (let [expires-at (.plusSeconds now ttl-seconds)]
    (is (nil? (admission/expiry-refusal (request :environment/expires-at expires-at) now)))
    (is (= :anomalies.agent-environment/request-expired
           (admission/expiry-refusal (request :environment/expires-at now) now)))))

(deftest ^{:stratum 2} test-capabilities-refuse-weaker-isolation-and-larger-limits
  (is (= [:anomalies.agent-environment/isolation-unavailable]
         (admission/capability-failures capabilities (request))))
  (is (= [:anomalies.agent-environment/limits-exceeded]
         (admission/capability-failures capabilities (request :environment/isolation :isolation/shared-kernel
                                                              :environment/limits {:limits/cpu-millicores 2000
                                                                                   :limits/memory-mib 512
                                                                                   :limits/ephemeral-storage-mib 1024})))))
