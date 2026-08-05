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
(ns ai.miniforge.opsv.domain-policy-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.opsv.interface :as opsv]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} risk-factors
  [{:factor :environment-class
    :input :staging
    :contribution 0.1
    :rationale "Staging is isolated"}
   {:factor :blast-radius
    :input {:services 2}
    :contribution 0.2
    :rationale "Two services are targeted"}
   {:factor :actuation-requested
    :input true
    :contribution 0.15
    :rationale "External mutation is requested"}
   {:factor :service-criticality
    :input :high
    :contribution 0.1
    :rationale "The service is customer-facing"}])

(def ^{:stratum 0} risk-thresholds
  {:medium 0.25 :high 0.5 :critical 0.75})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-assess-risk-is-additive-and-explainable
  (let [result (opsv/assess-risk risk-factors risk-thresholds)]
    (is (= 0.55 (:score result)))
    (is (= :high (:level result)))
    (is (= risk-factors (:factors result)))))

(deftest ^{:stratum 1} test-assess-risk-normalizes-score
  (let [factors (mapv #(assoc % :contribution 0.4) risk-factors)
        result (opsv/assess-risk factors risk-thresholds)]
    (is (= 1.0 (:score result)))
    (is (= :critical (:level result)))))

(deftest ^{:stratum 1} test-assess-risk-validates-transparent-policy-input
  (testing "required factors are present exactly once"
    (is (anomaly/anomaly?
         (opsv/assess-risk (vec (rest risk-factors)) risk-thresholds)))
    (is (anomaly/anomaly?
         (opsv/assess-risk (conj risk-factors (first risk-factors))
                           risk-thresholds))))
  (testing "contributions are normalized and thresholds are ordered"
    (is (anomaly/anomaly?
         (opsv/assess-risk
          (assoc-in risk-factors [0 :contribution] 1.1)
          risk-thresholds)))
    (is (anomaly/anomaly?
         (opsv/assess-risk risk-factors
                           {:medium 0.5 :high 0.25 :critical 0.75})))))
