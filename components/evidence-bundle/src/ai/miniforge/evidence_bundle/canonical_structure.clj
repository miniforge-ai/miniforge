;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.canonical-structure
  "Compose canonical schema reports without copying domain schemas."
  (:require [ai.miniforge.evidence-bundle.schema :as schema]
            [ai.miniforge.evidence-bundle.schema.domain :as domain]
            [ai.miniforge.evidence-bundle.schema.opsv :as opsv]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-fields
  [:evidence/plan :evidence/design :evidence/implement :evidence/verify
   :evidence/review :evidence/release :evidence/observe])

(def ^{:stratum 0} collection-fields
  {:evidence/tool-invocations domain/tool-invocation-schema
   :evidence/pack-promotions domain/pack-promotion-schema
   :evidence/supervision-decisions domain/supervision-decision-schema
   :evidence/control-actions domain/control-action-evidence-schema
   :evidence/rules-applied domain/rule-applied-schema})

(defn- ^{:stratum 0} field-errors [field-schema value]
  (:errors (validation/validate-schema field-schema value)))

(defn- ^{:stratum 0} opsv-errors [bundle]
  (when (and (contains? bundle :evidence/opsv)
             (not (m/validate opsv/OpsvEvidence (:evidence/opsv bundle))))
    [{:code :invalid-opsv-evidence}]))

(defn- ^{:stratum 0} semantic-intent-errors [bundle]
  (let [semantic (:evidence/semantic-validation bundle)]
    (when (and semantic
               (not (and (contains? domain/intent-types (:semantic-validation/declared-intent semantic))
                         (contains? domain/intent-types (:semantic-validation/actual-behavior semantic))
                         (= (get-in bundle [:evidence/intent :intent/type])
                          (:semantic-validation/declared-intent semantic)))))
      [{:code :invalid-semantic-intent}])))

(defn- ^{:stratum 0} semantic-count-errors [bundle]
  (let [value (:evidence/semantic-validation bundle)]
    (when (and value (not (domain/consistent-semantic-conclusion? value)))
      [{:code :inconsistent-semantic-conclusion}])))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} structured-fields
  (into {:evidence/semantic-validation domain/semantic-validation-schema}
        (zipmap phase-fields (repeat domain/phase-evidence-schema))))

(defn- ^{:stratum 1} structured-errors [bundle [field field-schema]]
  (when (contains? bundle field)
    (field-errors field-schema (get bundle field))))

(defn- ^{:stratum 1} collection-errors [bundle [field field-schema]]
  (mapcat (partial field-errors field-schema) (get bundle field)))

(defn- ^{:stratum 1} phase-output-errors [bundle field]
  (when (contains? bundle field)
    (concat
      (field-errors domain/phase-output-schema (get-in bundle [field :phase/output]))
      (when (not= (keyword (name field)) (get-in bundle [field :phase/name]))
        [{:code :phase-name-mismatch :field field}]))))

(defn- ^{:stratum 1} policy-errors [check]
  (concat (field-errors domain/policy-check-schema check)
          (mapcat (partial field-errors domain/violation-schema)
                  (:policy-check/violations check))))

(defn- ^{:stratum 1} intent-errors [intent]
  (concat (field-errors domain/intent-schema intent)
          (mapcat (partial field-errors domain/constraint-schema)
                  (:intent/constraints intent))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} errors [bundle]
  (vec (concat (field-errors schema/evidence-bundle-schema bundle)
               (intent-errors (:evidence/intent bundle))
               (semantic-intent-errors bundle)
               (semantic-count-errors bundle)
               (field-errors domain/outcome-schema (:evidence/outcome bundle))
               (mapcat policy-errors (:evidence/policy-checks bundle))
               (mapcat (partial field-errors domain/violation-schema)
                       (get-in bundle [:evidence/semantic-validation :semantic-validation/violations]))
               (mapcat (partial structured-errors bundle) structured-fields)
               (mapcat (partial collection-errors bundle) collection-fields)
               (mapcat (partial phase-output-errors bundle) phase-fields)
               (opsv-errors bundle))))

(comment
  (errors {}))
