;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.optional-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.collectors :as collectors]
            [ai.miniforge.evidence-bundle.outcome :as outcome]
            [ai.miniforge.evidence-bundle.producer-fixtures :as fixtures]
            [ai.miniforge.evidence-bundle.producer-roundtrips :as roundtrips]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} outcome-with [field value]
  (outcome/build-outcome-evidence (fixtures/workflow-state {field value})))

(deftest ^{:stratum 0} execution-omits-unavailable-fields
  (let [output (fixtures/incomplete-execution-output)
        result (collectors/collect-execution-evidence {:execution/output output})]
    (is (= {:evidence/execution-mode :local :evidence/task-finished-at fixtures/finished-at} result))))

(deftest ^{:stratum 0} execution-preserves-malformed-present-values-for-validation
  (doseq [value [false 0 ""]]
    (let [output (assoc (fixtures/incomplete-execution-output) :evidence/runtime-class value)
          result (collectors/collect-execution-evidence {:execution/output output})]
      (is (= value (:evidence/runtime-class result))))))

(deftest ^{:stratum 0} assembled-bundles-keep-producer-values-without-unavailable-fields
  (doseq [bundle (roundtrips/collected-bundles)]
    (is (= fixtures/pr-number (get-in bundle [:evidence/outcome :outcome/pr-number])))
    (is (= fixtures/pr-url (get-in bundle [:evidence/outcome :outcome/pr-url])))
    (is (not (contains? bundle :evidence/runtime-class)))
    (is (not (contains? bundle :evidence/task-started-at)))
    (is (not (contains? (:evidence/outcome bundle) :outcome/error-phase)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} release-pr-metadata-reaches-outcome
  (let [released (response/success {:workflow/pr-info (fixtures/release-info)})
        info (response/release-pr-info {:result released})
        result (outcome-with :workflow/pr-info info)]
    (is (= {:outcome/success true :outcome/pr-number fixtures/pr-number :outcome/pr-url fixtures/pr-url} result))))

(deftest ^{:stratum 1} legacy-pr-metadata-remains-readable
  (let [result (outcome-with :workflow/pr-info (fixtures/legacy-pr-info))]
    (is (= fixtures/pr-number (:outcome/pr-number result)))
    (is (= fixtures/pr-url (:outcome/pr-url result)))
    (is (= :merged (:outcome/pr-status result)))
    (is (= fixtures/finished-at (:outcome/pr-merged-at result)))))

(deftest ^{:stratum 1} producer-keys-take-precedence-without-coercion
  (doseq [value [false 0 ""]]
    (let [info (assoc (fixtures/legacy-pr-info) :pr-number value)
          result (outcome-with :workflow/pr-info info)]
      (is (= value (:outcome/pr-number result)))))
  (let [info (assoc (fixtures/legacy-pr-info) :pr-number nil)
        result (outcome-with :workflow/pr-info info)]
    (is (not (contains? result :outcome/pr-number)))))

(deftest ^{:stratum 1} absent-pr-and-error-metadata-do-not-create-fields
  (doseq [info [nil {}]]
    (is (= {:outcome/success true} (outcome-with :workflow/pr-info info))))
  (is (= {:outcome/success true} (outcome-with :workflow/error nil))))

(deftest ^{:stratum 1} phaseless-anomalies-omit-phase
  (doseq [error [(anomaly/anomaly :fault "Failure" {})
                 (response/make-anomaly :anomalies/fault "Failure")]
          shape [error {:anomaly error}]]
    (let [result (outcome-with :workflow/error shape)]
      (is (false? (:outcome/success result)))
      (is (= "Failure" (:outcome/error-message result)))
      (is (not (contains? result :outcome/error-phase))))))

(deftest ^{:stratum 1} legacy-errors-preserve-opaque-details
  (testing "omission is shallow and does not sanitize invalid present fields"
    (let [error {:message "Failure" :phase nil :details {:missing nil}}
          result (outcome-with :workflow/error error)]
      (is (= error (:outcome/error-details result)))
      (is (not (contains? result :outcome/error-phase))))
    (let [result (outcome-with :workflow/error {:phase false :message nil})]
      (is (false? (:outcome/error-phase result)))
      (is (not (contains? result :outcome/error-message))))))
