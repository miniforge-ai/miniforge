;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.representation
  "The finite value domain inspectable by redaction, including keys and metadata."
  (:import [clojure.lang BigInt Keyword Ratio Symbol]
           [java.math BigDecimal BigInteger]
           [java.time Instant]
           [java.util Date UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private maximum-depth
  "Reject nesting before the recursive redactor can exhaust the stack."
  128)

(def ^{:stratum 0} ^:private maximum-nodes
  "Bound structural inspection work, including map keys, values and metadata."
  100000)

(def ^{:stratum 0} ^:private scalar-classes
  "Concrete built-in scalar representations; opaque subclasses are not data."
  #{Boolean String Character Keyword Symbol UUID Date Instant
    Byte Short Integer Long Float Double BigDecimal BigInteger BigInt Ratio})

(def ^{:stratum 0} ^:private collection-classes
  "Concrete collections whose traversal exposes all payload values to redaction."
  ;; Derive classes from built-ins so JVM and Babashka need no private-class imports.
  (set (map class [{} (hash-map :key nil) (sorted-map) #{} (sorted-set)
                   [] (subvec [nil] 0) (first {:key nil}) '(nil) '()])))

(defn- ^{:stratum 0} children [value]
  (cond-> (cond (map? value) (mapcat identity value)
               (coll? value) (seq value))
    (meta value) (conj (meta value))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} inspectable? [value]
  (or (nil? value) (contains? scalar-classes (class value))
      (contains? collection-classes (class value))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} supported?
  "Reject opaque/deferred values and inputs exceeding 128 levels or 100000 nodes."
  [value]
  (loop [pending (list [value 0]) visited 0]
    (if-let [[item depth] (first pending)]
      (cond
        (or (>= visited maximum-nodes) (> depth maximum-depth)) false
        (not (inspectable? item)) false
        :else (recur (concat (map #(vector % (inc depth)) (children item)) (next pending))
                     (inc visited)))
      true)))

(comment
  (supported? {:extension/data [1 2 3]}))
