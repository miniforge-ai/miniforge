;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-rewrite
  (:require [ai.miniforge.phase-opsv.kubernetes-volume-references :as references]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} rename-binding [plan value]
  (let [name (get value "name")]
    (assoc value "name" (get plan name name))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} collection [plan value field]
  (if (contains? value field)
    (update value field (partial mapv (partial rename-binding plan)))
    value))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} container [plan value]
  (reduce (partial collection plan) value references/reference-fields))

(comment ::rewrite-only-admitted-name-bindings-never-content-or-unrelated-fields)
