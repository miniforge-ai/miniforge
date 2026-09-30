;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-identity
  "Order-independent map/set comparison retaining Transit collection and scalar kinds."
  (:require [ai.miniforge.artifact.publication-codec :as codec]
            [ai.miniforge.artifact.publication-boundary :as boundary]
            [ai.miniforge.content-hash.interface :as hash])
  (:import [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} identity-value [value]
  (cond
    (map? value) [:map (into {} (map (fn [[key item]] [(identity-value key) (identity-value item)])) value)]
    (set? value) [:set (into #{} (map identity-value) value)]
    (vector? value) [:vector (mapv identity-value value)]
    (seq? value) [:list (mapv identity-value value)]
    :else [:scalar (String. ^bytes (codec/encode value) StandardCharsets/UTF_8)]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} same-content? [expected actual]
  (and (= expected actual) (= (identity-value expected) (identity-value actual))))

(defn- ^{:stratum 1} content-digest [value]
  (if (codec/encode value)
    (hash/content-hash (identity-value value))
    (boundary/failure :invalid-input :publication/invalid nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} digest-with-exception-handling [value]
  (boundary/call-with-exception-handling nil :invalid-input :publication/invalid
                                        (partial content-digest value)))

(comment
  (digest-with-exception-handling {:measurement [1 2 3]}))
