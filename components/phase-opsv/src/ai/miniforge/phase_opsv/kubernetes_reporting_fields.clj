;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-fields
  (:require [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} update-present [value key transform]
  (if (and (map? value) (contains? value key)) (update value key transform) value))

(defn ^{:stratum 0} node-supported? [value]
  (and (nil? (meta value))
       (cond
         (map? value) (every? #(or (string? %) (keyword? %)) (keys value))
         (vector? value) true
         :else (not (coll? value)))))

(defn ^{:stratum 0} confidential [value]
  ;; Keep original values available to the shared scanner before redaction.
  ;; A collection also classifies numeric/null payloads without altering shared key policy.
  {policy/confidential-key [value]})

(defn ^{:stratum 0} classified-protected? [marker value]
  (every? #(or (not (map? %)) (not (contains? % policy/confidential-key)) (= marker (get % policy/confidential-key)))
          (tree-seq coll? seq value)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} protected-entry [allowed [key value]]
  [key (if (contains? allowed key) value (confidential value))])

(defn ^{:stratum 1} supported? [value]
  (and (redaction/supported? value) (every? node-supported? (tree-seq coll? seq value))))

(defn ^{:stratum 1} vector-values [transform value]
  (if (vector? value) (mapv transform value) (confidential value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} retain-fields [allowed value]
  (if (map? value)
    (into (empty value) (map (partial protected-entry allowed)) value)
    (confidential value)))
