;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reporting-model
  (:require [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]
            [ai.miniforge.phase-opsv.kubernetes-reporting-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resource? [value]
  (some #(or (contains? value %) (contains? value (keyword %))) policy/resource-shape-fields))

(defn- ^{:stratum 0} reference? [value]
  (and (string? (get value "name")) (string? (get value "uid")) (not (contains? value "metadata"))))

(defn- ^{:stratum 0} entry [transform [key value]]
  [key (if (contains? policy/confidential-semantic-fields key) (fields/confidential value) (transform value))])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} node [value]
  (cond
    (not (coll? value)) value
    (vector? value) (mapv node value)
    (reference? value) (fields/retain-fields policy/reference-fields value)
    (resource? value) (resource/project value)
    :else (into {} (map (partial entry node)) value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reporting-copy [value]
  (let [classified (node (fields/ordinary-maps value))]
    {:kubernetes/reporting-profile policy/profile
     :kubernetes/reporting classified}))

(comment (reporting-copy {"kind" "Pod"}))
