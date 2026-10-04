;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-shape
  "Reject deferred sequences before Transit attempts to count their elements."
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} scalar? [value]
  (or (nil? value) (boolean? value) (string? value) (keyword? value)
      (symbol? value) (number? value) (uuid? value) (inst? value)
      (instance? Instant value)))

(defn- ^{:stratum 0} collection? [value]
  (or (map? value) (vector? value) (set? value) (list? value)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} bounded-data? [value limit]
  (loop [pending [[value 0]] visited 0]
    (if-let [[item depth] (first pending)]
      (cond
        (or (>= visited limit) (> depth 128)) false
        (scalar? item) (recur (next pending) (inc visited))
        (collection? item)
        (recur (concat (map #(vector % (inc depth)) item) (next pending)) (inc visited))
        :else false)
      true)))
