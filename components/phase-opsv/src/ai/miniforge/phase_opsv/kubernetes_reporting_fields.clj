;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-fields
  (:require [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private confidential-key
  "Producer-owned classification key; its name uses the shared secret-key policy and its namespace isolates the proof."
  :kubernetes.reporting/secret)

(defn ^{:stratum 0} update-present [value key transform]
  (if (and (map? value) (contains? value key)) (update value key transform) value))

(defn ^{:stratum 0} node-supported? [value]
  (and (nil? (meta value))
       (cond
         (map? value) (every? #(or (string? %) (keyword? %)) (keys value))
         (vector? value) true
         :else (not (coll? value)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} confidential [value]
  ;; Keep original values available to the shared scanner before redaction.
  ;; A collection also classifies numeric/null payloads without altering shared key policy.
  {confidential-key [value]})

(defn ^{:stratum 1} classified-protected? [marker value]
  (every? #(or (not (map? %)) (not (contains? % confidential-key)) (= marker (get % confidential-key)))
          (tree-seq coll? seq value)))

(defn ^{:stratum 1} supported? [value]
  (and (redaction/supported? value) (every? node-supported? (tree-seq coll? seq value))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} retain-fields [allowed value]
  (if (map? value)
    (into (empty value) (map (fn [[key item]] [key (if (contains? allowed key) item (confidential item))])) value)
    (confidential value)))

(defn ^{:stratum 2} vector-values [transform value]
  (if (vector? value) (mapv transform value) (confidential value)))
