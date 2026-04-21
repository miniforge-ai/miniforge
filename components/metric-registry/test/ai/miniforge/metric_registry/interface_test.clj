(ns ai.miniforge.metric-registry.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.metric-registry.interface :as mr]))

(def ^:private sample-metric
  {:metric/id              "T10Y2Y"
   :metric/name            "10Y-2Y Treasury Spread"
   :metric/source-type     :public_canonical
   :metric/source-system   "FRED"
   :metric/refresh-cadence :daily
   :metric/implementation-mode :pull
   :metric/license-required false
   :metric/redistribution-risk :none
   :metric/direction       :normal
   :metric/unit            :percent})

(def ^:private sample-house-metric
  {:metric/id              "BUFFETT_INDICATOR"
   :metric/name            "Buffett Indicator (Market Cap / GDP)"
   :metric/source-type     :house_factor
   :metric/source-system   "internal"
   :metric/refresh-cadence :quarterly
   :metric/implementation-mode :derive
   :metric/license-required false
   :metric/redistribution-risk :none
   :metric/direction       :inverse
   :metric/unit            :ratio})

(def ^:private sample-registry
  {:registry/id      "risk-metrics"
   :registry/version "2026.03.17"
   :registry/families
   [{:family/id       :yield-curve
     :family/name     "Yield Curve & Credit Spreads"
     :family/metrics  [sample-metric]}
    {:family/id       :house-factors
     :family/name     "House Factors"
     :family/metrics  [sample-house-metric]}]
   :registry/pipeline-metric-map
   {"fred-risk-data" ["T10Y2Y" "BUFFETT_INDICATOR"]}})

;; -- Schema validation --

(deftest valid-metric-test
  (testing "Valid public canonical metric passes"
    (is (true? (mr/valid-metric? sample-metric))))

  (testing "Valid house factor metric passes"
    (is (true? (mr/valid-metric? sample-house-metric))))

  (testing "Missing required field fails"
    (is (false? (mr/valid-metric? (dissoc sample-metric :metric/source-type)))))

  (testing "Invalid enum value fails"
    (is (false? (mr/valid-metric? (assoc sample-metric :metric/source-type :invalid))))))

(deftest valid-family-test
  (testing "Valid family passes"
    (is (true? (mr/valid-family?
                {:family/id :yield-curve
                 :family/name "Yield Curve"
                 :family/metrics [sample-metric]}))))

  (testing "Empty metrics vector passes"
    (is (true? (mr/valid-family?
                {:family/id :empty
                 :family/name "Empty"
                 :family/metrics []})))))

(deftest validate-registry-test
  (testing "Valid registry passes"
    (let [result (mr/validate-registry sample-registry)]
      (is (true? (:valid? result)))
      (is (nil? (:errors result)))))

  (testing "Missing registry/id fails"
    (let [result (mr/validate-registry (dissoc sample-registry :registry/id))]
      (is (false? (:valid? result)))
      (is (some? (:errors result)))))

  (testing "Invalid metric within family fails"
    (let [bad (assoc-in sample-registry
                        [:registry/families 0 :family/metrics 0 :metric/source-type]
                        :invalid)
          result (mr/validate-registry bad)]
      (is (false? (:valid? result))))))

;; -- Enum value sets --

(deftest enum-sets-test
  (testing "Acquisition classes"
    (is (= [:public_canonical :official_market :premium_benchmark :house_factor]
           mr/acquisition-classes)))

  (testing "Implementation modes"
    (is (= [:pull :derive :vendor :deprecated]
           mr/implementation-modes)))

  (testing "Refresh cadences"
    (is (= [:realtime :daily :weekly :monthly :quarterly :annual]
           mr/refresh-cadences))))

;; -- Lookup functions --

(deftest all-metrics-test
  (testing "Returns all metrics across families"
    (is (= 2 (count (mr/all-metrics sample-registry))))))

(deftest find-metric-test
  (testing "Find existing metric by id"
    (let [m (mr/find-metric sample-registry "T10Y2Y")]
      (is (some? m))
      (is (= "10Y-2Y Treasury Spread" (:metric/name m)))))

  (testing "Returns nil for missing metric"
    (is (nil? (mr/find-metric sample-registry "NONEXISTENT")))))

(deftest find-metrics-by-family-test
  (testing "Find metrics in yield-curve family"
    (let [ms (mr/find-metrics-by-family sample-registry :yield-curve)]
      (is (= 1 (count ms)))
      (is (= "T10Y2Y" (:metric/id (first ms))))))

  (testing "Returns nil for missing family"
    (is (nil? (mr/find-metrics-by-family sample-registry :nonexistent)))))

(deftest find-metrics-by-source-type-test
  (testing "Find public canonical metrics"
    (let [ms (mr/find-metrics-by-source-type sample-registry :public_canonical)]
      (is (= 1 (count ms)))
      (is (= "T10Y2Y" (:metric/id (first ms))))))

  (testing "Find house factors"
    (let [ms (mr/find-metrics-by-source-type sample-registry :house_factor)]
      (is (= 1 (count ms)))
      (is (= "BUFFETT_INDICATOR" (:metric/id (first ms))))))

  (testing "Returns empty for unmatched source type"
    (is (empty? (mr/find-metrics-by-source-type sample-registry :premium_benchmark)))))

(deftest find-metrics-by-pipeline-test
  (testing "Find metrics for known pipeline"
    (is (= ["T10Y2Y" "BUFFETT_INDICATOR"]
           (mr/find-metrics-by-pipeline sample-registry "fred-risk-data"))))

  (testing "Returns nil for unknown pipeline"
    (is (nil? (mr/find-metrics-by-pipeline sample-registry "unknown")))))

(deftest family-ids-test
  (testing "Returns all family ids"
    (is (= [:yield-curve :house-factors]
           (mr/family-ids sample-registry)))))

(deftest metric-count-test
  (testing "Counts all metrics"
    (is (= 2 (mr/metric-count sample-registry)))))
