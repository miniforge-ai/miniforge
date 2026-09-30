;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.canonical-validation-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} base-bundle []
  {:evidence-bundle/id (random-uuid) :evidence-bundle/workflow-id (random-uuid)
   :evidence-bundle/created-at #inst "2026-09-30T00:00:00Z" :evidence-bundle/version "1.0.0"
   :evidence/intent {:intent/type :update :intent/description "Adjust capacity."
                     :intent/business-reason "Meet latency objectives." :intent/constraints []
                     :intent/declared-at #inst "2026-09-30T00:00:00Z"}
   :evidence/policy-checks [] :evidence/outcome {:outcome/success true}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} canonical-validation-checks-required-types-and-domain-values-test
  (let [bundle (base-bundle)]
    (is (:valid? (evidence/validate-canonical-bundle bundle)))
    (doseq [key (keys bundle)]
      (is (false? (:valid? (evidence/validate-canonical-bundle (dissoc bundle key))))))
    (doseq [[path value] [[[:evidence-bundle/id] "not-a-uuid"]
                          [[:evidence-bundle/created-at] "not-an-instant"]
                          [[:evidence-bundle/version] 1]
                          [[:evidence/intent :intent/type] :unknown]
                          [[:evidence/intent :intent/constraints] 42]
                          [[:evidence/outcome :outcome/success] "true"]
                          [[:evidence/policy-checks] [{}]]
                          [[:evidence/opsv] {}]]]
      (is (false? (:valid? (evidence/validate-canonical-bundle (assoc-in bundle path value))))))))

(deftest ^{:stratum 1} declared-hash-is-verified-without-claiming-authority-test
  (let [bundle (base-bundle)
        sealed (assoc bundle :evidence/content-hash (evidence/content-hash bundle))]
    (is (:valid? (evidence/validate-canonical-bundle sealed)))
    (doseq [altered [(assoc-in sealed [:evidence/outcome :outcome/success] false)
                     (assoc sealed :evidence/content-hash nil)
                     (assoc sealed :evidence/content-hash "wrong")]]
      (is (false? (:valid? (evidence/validate-canonical-bundle altered)))))))

(deftest ^{:stratum 1} field-presence-is-distinct-from-nullability-test
  (let [check {:policy-check/pack-id "opsv"
               :policy-check/pack-version "1.0.0"
               :policy-check/phase :verify
               :policy-check/checked-at #inst "2026-09-30T00:00:00Z"
               :policy-check/violations []
               :policy-check/passed? true
               :policy-check/duration-ms 0
               :policy-check/envelope nil}
        bundle (assoc (base-bundle) :evidence/policy-checks [check])]
    (is (:valid? (evidence/validate-canonical-bundle bundle)))
    (is (false? (:valid? (evidence/validate-canonical-bundle
                         (update-in bundle [:evidence/policy-checks 0]
                                    dissoc :policy-check/envelope)))))
    (is (false? (:valid? (evidence/validate-canonical-bundle
                         (assoc-in bundle [:evidence/intent :intent/author] nil)))))))

(deftest ^{:stratum 1} nonportable-or-nonmap-input-is-rejected-before-hashing-test
  (doseq [value [nil [] 42 (assoc (base-bundle) :extension (Object.))
                 (assoc (base-bundle) :extension (iterate inc 0))]]
    (is (false? (:valid? (evidence/validate-canonical-bundle value))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.canonical-validation-test))
