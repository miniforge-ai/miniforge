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
(ns ai.miniforge.policy-clause.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-clause.interface :as clause]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} vocabulary-shape-test
  (testing "severity scale, most to least severe"
    (is (= [:critical :high :medium :low :info] clause/severities))
    (is (= 0 (get clause/severity-order :critical)))
    (is (= 4 (get clause/severity-order :info))))
  (testing "enforcement actions, strictest first"
    (is (= [:hard-halt :require-approval :warn :audit] clause/enforcement-actions))
    (is (= 0 (get clause/enforcement-order :hard-halt)))))

(deftest ^{:stratum 0} normalization-test
  (is (= :high (clause/normalize-severity :major)))
  (is (= :low (clause/normalize-severity :minor)))
  (is (= :medium (clause/normalize-severity :medium))))

(deftest ^{:stratum 0} unknown-values-are-anomalies-test
  (testing "rank: no rank-99 default survives"
    (let [a (clause/rank-severity :wobbly)]
      (is (anomaly/anomaly? a))
      (is (= :anomalies.policy-clause/unknown-severity (anomaly/subtype a))))
    (is (= 1 (clause/rank-severity :major))))
  (testing "compare: unknown on either side is an anomaly"
    (is (anomaly/anomaly? (clause/compare-severity :critical :shaky)))
    (is (anomaly/anomaly? (clause/compare-severity :shaky :critical)))
    (is (neg? (clause/compare-severity :critical :high))))
  (testing "more-severe"
    (is (= :critical (clause/more-severe :critical :low)))
    (is (= :critical (clause/more-severe :low :critical)))
    (is (anomaly/anomaly? (clause/more-severe :low :wobbly)))))

(deftest ^{:stratum 0} sort-by-severity-test
  (is (= [{:s :critical} {:s :medium} {:s :info}]
         (clause/sort-by-severity :s [{:s :info} {:s :critical} {:s :medium}])))
  (testing "one unknown severity poisons the whole sort, loudly"
    (is (anomaly/anomaly? (clause/sort-by-severity :s [{:s :info} {:s :wobbly}])))))

(deftest ^{:stratum 0} axis-crossing-test
  (testing "mechanical -> policy"
    (is (= :high (clause/mechanical->severity :error)))
    (is (= :medium (clause/mechanical->severity :warning)))
    (is (= :info (clause/mechanical->severity :note)))
    (is (anomaly/anomaly? (clause/mechanical->severity :none)))
    (is (anomaly/anomaly? (clause/mechanical->severity :meltdown))))
  (testing "policy -> mechanical"
    (is (= :error (clause/severity->mechanical :critical)))
    (is (= :error (clause/severity->mechanical :high)))
    (is (= :warning (clause/severity->mechanical :low)))
    (is (= :note (clause/severity->mechanical :info)))
    (is (anomaly/anomaly? (clause/severity->mechanical :wobbly)))))

(deftest ^{:stratum 0} schema-test
  (is (m/validate clause/Severity :critical))
  (is (not (m/validate clause/Severity :wobbly)))
  (is (m/validate clause/EnforcementAction :require-approval))
  (is (not (m/validate clause/EnforcementAction :block))))
