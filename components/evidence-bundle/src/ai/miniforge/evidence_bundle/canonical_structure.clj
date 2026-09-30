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

(defn- ^{:stratum 0} field-errors [field-schema value]
  (:errors (validation/validate-schema field-schema value)))

(defn- ^{:stratum 0} opsv-errors [bundle]
  (when (and (contains? bundle :evidence/opsv)
             (not (m/validate opsv/OpsvEvidence (:evidence/opsv bundle))))
    [{:code :invalid-opsv-evidence}]))

;------------------------------------------------------------------------------ Layer 1

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
               (field-errors domain/outcome-schema (:evidence/outcome bundle))
               (mapcat policy-errors (:evidence/policy-checks bundle))
               (opsv-errors bundle))))

(comment
  (errors {}))
