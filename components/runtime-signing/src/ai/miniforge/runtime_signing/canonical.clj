;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.canonical
  "A lossless, deterministic text encoding of plain data for signing.

   Map entries and set members are sorted by their own encodings, and
   strings are printed readably whatever the caller's printer bindings.
   A map or set holding two entries that would encode alike (an Instant
   and a Date at the same moment, say) is refused rather than collapsed,
   so two distinct values never share an encoding."
  (:require
   [clojure.string :as str])
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refuse! [x]
  (throw (IllegalArgumentException.
          (str "cannot encode for signing: " (.getName (class x))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} scalar
  "Encoding of one non-collection value, or a refusal."
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x) (uuid? x)
        (ratio? x) (decimal? x))
    (pr-str x)

    (integer? x) (str (biginteger x))
    (float? x) (pr-str (double x))
    (instance? Instant x) (str "#inst " (pr-str (str x)))
    (instance? Date x) (str "#inst " (pr-str (str (.toInstant ^Date x))))
    :else (refuse! x)))

(defn- ^{:stratum 1} distinct-sorted
  "Sorted encodings, refusing when two of `n` entries encode alike."
  [x encodings n]
  (let [sorted (sort encodings)]
    (if (= n (count (distinct sorted))) sorted (refuse! x))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} encode
  "The encoding of `x`. Throws IllegalArgumentException for a value it
   cannot encode losslessly."
  [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-namespace-maps* false *print-dup* false *print-readably* true]
    (letfn [(enc [v]
              (cond
                (map? v)
                (let [ks (distinct-sorted v (map (comp enc key) v) (count v))
                      by-key (into {} (map (fn [[k item]] [(enc k) (enc item)])) v)]
                  (str "{" (str/join ", " (map #(str % " " (by-key %)) ks)) "}"))

                (set? v) (str "#{" (str/join " " (distinct-sorted v (map enc v) (count v))) "}")
                (sequential? v) (str "[" (str/join " " (map enc v)) "]")
                :else (scalar v)))]
      (enc x))))

(comment
  (encode {:b #{1 1.0} :a "x"}))
