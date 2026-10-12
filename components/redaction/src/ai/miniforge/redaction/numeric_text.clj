;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.redaction.numeric-text
  "Bound numeric rendering before secret detection expands scientific notation."
  (:import [java.math BigDecimal]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private maximum-expansion-characters
  "Maximum expanded numeric text; larger representations retain compact notation."
  1024)

(defn- ^{:stratum 0} expanded-size [^BigDecimal value]
  (let [digits (long (.precision value))
        scale (long (.scale value))]
    (+ 1 (if (pos? scale) (max (inc digits) (+ 2 scale)) (- digits scale)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} decimal-text [^BigDecimal value]
  (if (<= (expanded-size value) maximum-expansion-characters)
    (.toPlainString value)
    (.toString value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} render [value]
  (cond
    (decimal? value) (decimal-text value)
    (and (float? value) (Double/isFinite (double value))) (decimal-text (bigdec value))
    :else (str value)))

(comment
  (render 4.111111111111111E15M))
