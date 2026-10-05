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

(def ^{:stratum 0} ^:private scalar-classes
  "Concrete built-in scalar representations; opaque subclasses are not data."
  #{Boolean String Character Keyword Symbol UUID Date Instant
    Byte Short Integer Long Float Double BigDecimal BigInteger BigInt Ratio})

(defn- ^{:stratum 0} children [value]
  (cond-> (when (coll? value) (seq value))
    (meta value) (conj (meta value))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} inspectable? [value]
  (and (not (record? value))
       (or (nil? value) (contains? scalar-classes (class value))
           (map? value) (vector? value) (set? value) (list? value))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} supported?
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
