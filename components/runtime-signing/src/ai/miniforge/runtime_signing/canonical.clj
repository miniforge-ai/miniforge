;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.canonical
  "A lossless, deterministic text encoding of plain data for signing.

   Map entries and set members are sorted by their own encodings, and
   strings are printed readably whatever the caller's printer bindings.
   Keywords and symbols carry a type tag and their namespace and name as
   quoted strings, since their printed form is not escaped: the symbol
   `nil` and nil, or the symbol `a b` and the symbols `a` `b`, would
   otherwise encode alike.
   A map or set holding two entries that would encode alike (an Instant
   and a Date at the same moment, say) is refused rather than collapsed,
   so two distinct values never share an encoding. Each key and value is
   encoded once, so the work is linear in the size of the value."
  (:require
   [clojure.string :as str])
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} max-depth
  "Deepest nesting the encoder accepts. Grants and envelopes nest a few
   levels; the bound keeps untrusted input from overflowing a verifier's
   stack, which `signed?` could not catch as an Exception."
  64)

(defn- ^{:stratum 0} refuse! [x]
  (throw (IllegalArgumentException.
          (str "cannot encode for signing: " (.getName (class x))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} scalar
  "Encoding of one non-collection value, or a refusal."
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (uuid? x) (ratio? x) (decimal? x))
    (pr-str x)

    (keyword? x) (str "#keyword" (pr-str [(namespace x) (name x)]))
    (symbol? x) (str "#symbol" (pr-str [(namespace x) (name x)]))

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
   cannot encode losslessly, or one nested deeper than `max-depth`."
  [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-namespace-maps* false *print-dup* false *print-readably* true]
    (letfn [(enc [v depth]
              (let [child #(enc % (inc depth))]
                (cond
                  (< max-depth depth) (refuse! v)

                  (map? v)
                  (let [entries (mapv (fn [[k item]] [(child k) item]) v)
                        by-key (into {} entries)
                        ks (distinct-sorted v (map first entries) (count v))]
                    (str "{" (str/join ", " (map #(str % " " (child (by-key %))) ks)) "}"))

                  (set? v) (str "#{" (str/join " " (distinct-sorted v (map child v) (count v))) "}")
                  (sequential? v) (str "[" (str/join " " (map child v)) "]")
                  :else (scalar v))))]
      (enc x 0))))

(comment
  (encode {:b #{1 1.0} :a "x"}))
