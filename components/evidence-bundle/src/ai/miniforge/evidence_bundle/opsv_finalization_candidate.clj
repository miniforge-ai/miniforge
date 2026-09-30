;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-finalization-candidate
  "Prepare portable canonical evidence and collect correlation errors before sealing."
  (:require [ai.miniforge.evidence-bundle.canonical-validation :as validation]
            [ai.miniforge.evidence-bundle.opsv-finalization-references :as references]
            [ai.miniforge.evidence-bundle.schema.opsv :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} candidate-bundle [record base evidence]
  (when (map? base)
    (-> base
        (dissoc :evidence/content-hash :evidence/signature)
        (assoc :evidence-bundle/id (:evidence-bundle/id record)
               :evidence/opsv evidence))))

(defn- ^{:stratum 0} bundle-errors [candidate]
  (map #(assoc % :code :invalid-evidence-bundle)
       (:errors (validation/validate-with-exception-handling candidate))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} validation-errors [record base evidence candidate available-ids schema-valid?]
  (cond-> []
    (not schema-valid?) (conj {:code :invalid-opsv-evidence})
    (not (map? base)) (conj {:code :invalid-base-bundle})
    (and (map? base) (not= (:evidence-bundle/workflow-id record) (:evidence-bundle/workflow-id base)))
    (conj {:code :workflow-reference-mismatch})
    schema-valid? (into (references/errors record evidence available-ids))
    candidate (into (bundle-errors candidate))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [record base evidence available-ids]
  (let [schema-valid? (m/validate schema/OpsvEvidence evidence)
        canonical (if schema-valid? (references/canonicalize evidence) evidence)
        bundle (candidate-bundle record base canonical)
        errors (validation-errors record base canonical bundle available-ids schema-valid?)]
    {:bundle bundle
     :errors errors}))

(comment
  (prepare {} {} {} #{}))
