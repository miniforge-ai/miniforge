;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.outcome-reliability-test
  (:require [ai.miniforge.evidence-bundle.schema.domain :as domain]
            [ai.miniforge.evidence-bundle.schema.outcome-reliability :as outcome]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]
            [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.reliability.interface :as reliability]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} measurement []
  {:sli/name :SLI-1 :sli/value 0.99})

(defn- ^{:stratum 0} valid-outcome? [fields]
  (:valid? (validation/validate-schema domain/outcome-schema
                                       (assoc fields :outcome/success true))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} optional-enumerations-use-canonical-domains
  (doseq [[field predicate values]
          [[:outcome/tier outcome/tier? (rest reliability/WorkflowTier)]
           [:outcome/degradation-mode outcome/degradation-mode? (rest reliability/DegradationMode)]
           [:outcome/failure-class outcome/failure-class? failure/failure-classes]]]
    (doseq [value values]
      (is (predicate value))
      (is (valid-outcome? {field value}))))
  (is (valid-outcome? {})))

(deftest ^{:stratum 1} invalid-enumerations-are-not-evidence
  (doseq [[field predicate] [[:outcome/tier outcome/tier?]
                            [:outcome/degradation-mode outcome/degradation-mode?]
                            [:outcome/failure-class outcome/failure-class?]]]
    (doseq [value [:bogus "standard" 42 false]]
      (is (not (predicate value)))
      (is (not (valid-outcome? {field value})))))
  (doseq [predicate [outcome/tier? outcome/degradation-mode? outcome/failure-class?]]
    (is (not (predicate nil)))))

(deftest ^{:stratum 1} measurement-fields-follow-n6-not-windowed-sli-results
  (doseq [name (rest reliability/SliName)
          extra [{} {:sli/target 0.98 :sli/met? true} {:sli/met? false}]]
    (let [value [(merge (measurement) extra {:sli/name name})]]
      (is (outcome/sli-measurements? value))
      (is (valid-outcome? {:outcome/sli-measurements value})))))

(deftest ^{:stratum 1} malformed-measurement-containers-and-entries-are-rejected
  (doseq [value [nil false 42 {} (list (measurement)) [nil] [{}]]]
    (is (not (outcome/sli-measurements? value))))
  (doseq [[field value] [[:sli/name :unknown] [:sli/name nil]
                        [:sli/value "0.99"] [:sli/value nil]
                        [:sli/target "0.98"] [:sli/target nil]
                        [:sli/met? :yes] [:sli/met? nil]]]
    (let [measurements [(assoc (measurement) field value)]]
      (is (not (outcome/sli-measurements? measurements)))
      (is (not (valid-outcome? {:outcome/sli-measurements measurements}))))))

(deftest ^{:stratum 1} measurement-required-fields-and-empty-collection
  (doseq [field [:sli/name :sli/value]]
    (is (not (outcome/sli-measurements? [(dissoc (measurement) field)]))))
  (is (outcome/sli-measurements? [])))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.outcome-reliability-test))
