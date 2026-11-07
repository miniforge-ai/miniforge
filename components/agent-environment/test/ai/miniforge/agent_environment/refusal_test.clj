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
(ns ai.miniforge.agent-environment.refusal-test
  (:require
   [ai.miniforge.agent-environment.refusal :as refusal]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} type-of [reason] (:anomaly/type (refusal/reason-anomaly reason {})))

(deftest ^{:stratum 0} test-every-reason-has-a-catalog-message
  (doseq [reason (conj (keys refusal/reason->type) :anomalies.agent-environment/provisioning-failed)]
    (testing (str "given " reason " → a catalog message, not the bare key name")
      (is (not= (name reason) (:anomaly/message (refusal/reason-anomaly reason {})))))))

(deftest ^{:stratum 0} test-conflicts-and-authority-failures-are-refusals
  (is (= :outcome/refused (refusal/result-outcome (anomaly/anomaly :conflict "x" {}))))
  (is (= :outcome/failed (refusal/result-outcome (anomaly/anomaly :not-found "x" {})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-each-reason-carries-its-type-and-subtype
  (is (= :unauthorized (type-of :anomalies.agent-environment/forged-tenant)))
  (is (= :conflict (type-of :anomalies.agent-environment/stale-lease)))
  (is (= :fault (type-of :anomalies.agent-environment/provisioning-failed)))
  (is (= :invalid-input (type-of :anomalies.agent-environment/invalid-input)))
  (is (= :anomalies.agent-environment/stale-lease
         (anomaly/subtype (refusal/reason-anomaly :anomalies.agent-environment/stale-lease {})))))
