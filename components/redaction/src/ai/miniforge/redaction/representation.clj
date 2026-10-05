;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.representation
  "The finite value domain inspectable by redaction, including keys and metadata."
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} inspectable? [value]
  (and (not (record? value))
       (or (nil? value) (boolean? value) (string? value) (char? value)
           (keyword? value) (symbol? value) (number? value) (uuid? value)
           (inst? value) (instance? Instant value)
           (map? value) (vector? value) (set? value) (list? value))))

(defn- ^{:stratum 0} children [value]
  (cond-> (when (coll? value) (seq value))
    (meta value) (conj (meta value))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} supported?
  "Reject opaque objects, records and deferred sequences without dereferencing them."
  [value]
  (loop [pending (list value)]
    (if (empty? pending)
      true
      (let [item (first pending)]
        (and (inspectable? item)
             (recur (concat (children item) (next pending))))))))

(comment
  (supported? {:extension/data [1 2 3]}))
