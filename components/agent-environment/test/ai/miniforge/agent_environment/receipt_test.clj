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
(ns ai.miniforge.agent-environment.receipt-test
  (:require
   [ai.miniforge.agent-environment.receipt :as receipt]
   [ai.miniforge.agent-environment.schema :as schema]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-11-06T00:00:00Z"))

(def ^{:stratum 0} acting
  {:acting/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :acting/principal-id #uuid "00000000-0000-0000-0000-0000000000a1"
   :acting/established-at "2026-11-06T00:00:00Z"})

(def ^{:stratum 0} request
  {:environment/tenant-id #uuid "00000000-0000-0000-0000-00000000000f"
   :environment/run-id #uuid "00000000-0000-0000-0000-00000000000b"
   :environment/task-id #uuid "00000000-0000-0000-0000-00000000000c"})

(defn- ^{:stratum 0} step [outcome] {:step/name :step/test :step/outcome outcome})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-steps-outcome-summarizes-provider-steps
  (testing "given every step applied (or none) → applied"
    (is (= :outcome/applied (receipt/steps-outcome [])))
    (is (= :outcome/applied (receipt/steps-outcome [(step :outcome/applied)]))))
  (testing "given a failure after something may have taken effect → partial"
    (is (= :outcome/partial (receipt/steps-outcome [(step :outcome/applied) (step :outcome/failed)])))
    (is (= :outcome/partial (receipt/steps-outcome [(step :outcome/uncertain) (step :outcome/failed)]))))
  (testing "given only a failure → failed; an unconfirmed step → uncertain, never applied"
    (is (= :outcome/failed (receipt/steps-outcome [(step :outcome/failed)])))
    (is (= :outcome/uncertain (receipt/steps-outcome [(step :outcome/applied) (step :outcome/uncertain)])))))

(deftest ^{:stratum 1} test-receipt-is-valid-unqualified-and-owned-by-the-actor
  (let [refused (receipt/receipt (receipt/request-subject acting request) :environment/acquire
                                 :outcome/refused [] :anomalies.agent-environment/forged-tenant now)]
    (is (m/validate schema/Receipt refused))
    (is (= :profile/unqualified (:receipt/profile refused)))
    (is (= (:acting/tenant-id acting) (:receipt/tenant-id refused)))
    (is (nil? (:receipt/environment-uid refused)))))
