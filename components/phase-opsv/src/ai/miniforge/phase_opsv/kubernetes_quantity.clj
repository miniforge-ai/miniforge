;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-quantity
  "Bounded exact positive resource values for comparison, never provider-style rounding or capping."
  (:require [slingshot.slingshot :refer [try+]])
  (:import [java.math BigDecimal]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} maximum-text-length
  "128 characters exceed canonical native Long-based quantities while bounding parsing work." 128)

(def ^{:stratum 0 :private true} maximum-exponent
  "64 exceeds the native Long/milli/MiB magnitude range without allowing enormous decimal scales." 64)

(def ^{:stratum 0 :private true} syntax
  #"\A([+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))([eE][+-]?[0-9]+|[numkMGTPE]|[KMGTPE]i)?\z")

(def ^{:stratum 0 :private true} suffix-factors
  "Kubernetes decimal and binary SI multipliers; decimal values remain exact."
  {"" 1M "n" 0.000000001M "u" 0.000001M "m" 0.001M
   "k" 1000M "M" 1000000M "G" 1000000000M "T" 1000000000000M
   "P" 1000000000000000M "E" 1000000000000000000M
   "Ki" 1024M "Mi" 1048576M "Gi" 1073741824M "Ti" 1099511627776M
   "Pi" 1125899906842624M "Ei" 1152921504606846976M})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} value [text]
  (let [bounded (when (and (string? text) (<= (count text) maximum-text-length)) text)
        [_ mantissa suffix] (some->> bounded (re-matches syntax))
        exponent (some-> suffix (subs 1) parse-long)
        ^BigDecimal factor (get suffix-factors (or suffix ""))]
    (try+
      (let [^BigDecimal amount
            (cond
              (nil? mantissa) nil
              factor (.multiply (BigDecimal. ^String mantissa) factor)
              (and (some? exponent) (<= (- maximum-exponent) exponent maximum-exponent))
              (BigDecimal. ^String (str mantissa suffix))
              :else nil)]
        (some-> amount .stripTrailingZeros))
      (catch NumberFormatException _ nil))))

(comment ::comparison-only-no-floating-point-no-rewriting-authorized-request-data)
