;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.collector-projection
  "Project legacy producer records onto canonical N6 evidence keys."
  (:require [ai.miniforge.evidence-bundle.protocols.impl.semantic-validator :as semantic]
            [ai.miniforge.schema.interface :as schema]
            [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} violation-keys
  {:rule-id :violation/rule-id :severity :violation/severity :message :violation/message
   :location :violation/location :remediation :violation/remediation :auto-fixable? :violation/auto-fixable?})

(defn ^{:stratum 0} semantic-evidence [intent artifacts]
  (set/rename-keys (semantic/validate-intent-impl intent artifacts)
                   {:passed? :semantic-validation/passed? :violations :semantic-validation/violations}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} violation [record]
  (if (contains? record :violation/rule-id)
    record
    (let [value (set/rename-keys (get record :violation record) violation-keys)
          id (or (:violation/rule-id value) (get-in record [:rule :rule/id]))
          rule-id (if (keyword? id) (str id) id)
          severity (schema/normalize-severity (or (:violation/severity value) (get-in record [:rule :rule/severity])))]
      (assoc value :violation/rule-id rule-id :violation/severity severity))))

(comment
  (semantic-evidence {:intent/type :import} []))
