;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-schema-test
  (:require [ai.miniforge.evidence-bundle.domain-fixtures :as fixtures]
            [ai.miniforge.evidence-bundle.schema.phase-linkage :as phase-linkage]
            [ai.miniforge.evidence-bundle.schema.publication :as publication]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} link [workflow-id]
  {:event-links/scope-type :workflow :event-links/scope-id workflow-id
   :event-links/from-sequence 10 :event-links/to-sequence 12 :event-links/event-count 3})

(defn- ^{:stratum 0} publication-valid? [bundle]
  (empty? (publication/errors bundle)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} bundle []
  (let [workflow-id (random-uuid)]
    {:evidence-bundle/workflow-id workflow-id
     :evidence/implement (fixtures/phase)
     :evidence/event-links [(link workflow-id)]
     :evidence/outcome {:outcome/tier :standard}}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} every-phase-range-belongs-to-the-matching-workflow-link
  (let [value (bundle)]
    (is (publication-valid? value))
    (doseq [field phase-linkage/phase-fields]
      (is (publication-valid? (assoc value field (fixtures/phase))))
      (doseq [range [{:start-seq 9 :end-seq 12} {:start-seq 10 :end-seq 13}
                     {:start-seq 100 :end-seq 110} nil]]
        (is (not (publication-valid? (assoc-in value [field :phase/event-stream-range] range))))))
    (is (publication-valid? (assoc-in value [:evidence/implement :phase/event-stream-range]
                                    {:start-seq 11 :end-seq 11})))
    (is (not (publication-valid? (dissoc value :evidence/event-links))))
    (is (not (publication-valid? (assoc value :evidence/implement nil))))
    (doseq [links [nil [] [{}] [(link (random-uuid))]
                   [(assoc (first (:evidence/event-links value)) :event-links/scope-type :pr)]]]
      (is (not (publication-valid? (assoc value :evidence/event-links links)))))))

(deftest ^{:stratum 2} publication-links-check-cardinality-types-and-unique-scopes
  (let [value (bundle)]
    (doseq [[field invalid] [[:event-links/scope-type :unknown] [:event-links/scope-id 42]
                             [:event-links/from-sequence -1] [:event-links/to-sequence 9]
                             [:event-links/event-count 2] [:event-links/event-count 4]]]
      (is (not (publication-valid? (assoc-in value [:evidence/event-links 0 field] invalid)))))
    (is (not (publication-valid? (update value :evidence/event-links into (:evidence/event-links value)))))
    (doseq [[scope id] [[:pr (random-uuid)] [:pack "pack"] [:repo "repo"] [:deployment "deploy"]
                        [:supervisory-entity ["repo" 1]]]]
      (let [extra (assoc (first (:evidence/event-links value))
                        :event-links/scope-type scope :event-links/scope-id id)]
        (is (publication-valid? (update value :evidence/event-links conj extra)))
        (is (not (publication-valid? (update value :evidence/event-links conj
                                            (assoc extra :event-links/scope-id 42)))))))))

(deftest ^{:stratum 2} only-unsealed-empty-assemblies-may-omit-publication-fields
  (is (publication-valid? {}))
  (doseq [seal [:evidence/content-hash :evidence/signature :evidence/sealed-at]]
    (is (not (publication-valid? {seal nil})))
    (is (publication-valid? (assoc (bundle) seal nil))))
  (doseq [tier [nil :unknown]]
    (is (not (publication-valid? (assoc-in (bundle) [:evidence/outcome :outcome/tier] tier))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.publication-schema-test))
