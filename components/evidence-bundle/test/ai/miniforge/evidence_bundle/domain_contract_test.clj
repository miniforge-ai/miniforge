;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.domain-contract-test
  (:require [ai.miniforge.evidence-bundle.domain-fixtures :as fixtures]
            [ai.miniforge.evidence-bundle.schema.domain :as domain]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]
            [ai.miniforge.evidence-bundle.semantic-report :as semantic]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} valid? [schema value]
  (:valid? (validation/validate-schema schema value)))

(deftest ^{:stratum 0} semantic-conclusions-match-shared-intent-rules
  (doseq [intent [:import :refactor :create :update :destroy :migrate]
          counts [{:creates 0 :updates 0 :destroys 0} {:creates 1 :updates 0 :destroys 1}
                  {:creates 2 :updates 1 :destroys 1}]]
    (let [report (semantic/build intent counts #inst "2026-10-01")
          value (set/rename-keys report {:passed? :semantic-validation/passed?
                                        :violations :semantic-validation/violations})]
      (is (domain/consistent-semantic-conclusion? value))
      (is (not (domain/consistent-semantic-conclusion? (update value :semantic-validation/passed? not)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} phases-require-real-ordered-ranges-and-artifact-vectors
  (let [record (fixtures/phase)]
    (is (valid? domain/phase-evidence-schema record))
    (is (not (valid? domain/phase-evidence-schema (dissoc record :phase/event-stream-range))))
    (doseq [range [nil {} {:start-seq 10} {:start-seq -1 :end-seq 1}
                   {:start-seq 12 :end-seq 10} {:start-seq "10" :end-seq 12}]]
      (is (not (valid? domain/phase-evidence-schema (assoc record :phase/event-stream-range range)))))
    (doseq [artifacts [nil '() [nil] ["artifact"]]]
      (is (not (valid? domain/phase-evidence-schema (assoc record :phase/artifacts artifacts)))))
    (is (valid? domain/phase-evidence-schema (assoc record :phase/artifacts [(random-uuid)])))))

(deftest ^{:stratum 1} semantic-violations-carry-required-location-and-repair-policy
  (let [report (semantic/build :import {:creates 1 :updates 0 :destroys 0} #inst "2026-10-01")
        violation (first (:violations report))]
    (is (valid? domain/violation-schema violation))
    (is (= {} (:violation/location violation)))
    (is (false? (:violation/auto-fixable? violation)))
    (doseq [field [:violation/location :violation/auto-fixable?]]
      (is (not (valid? domain/violation-schema (dissoc violation field))))
      (is (not (valid? domain/violation-schema (assoc violation field nil)))))
    (is (not (valid? domain/violation-schema (assoc violation :violation/auto-fixable? :yes))))))

(deftest ^{:stratum 1} phase-output-validates-known-projections-without-requiring-result-status
  (is (valid? domain/phase-output-schema {}))
  (is (valid? domain/phase-output-schema {:summary "Done" :metrics {} :status :success}))
  (doseq [value [{:summary 42} {:metrics []} {:status :unknown} {:environment-id 42}]]
    (is (not (valid? domain/phase-output-schema value)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.domain-contract-test))
