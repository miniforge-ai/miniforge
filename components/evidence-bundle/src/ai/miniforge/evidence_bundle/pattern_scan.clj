;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.pattern-scan
  "Match original scalar values and metadata, never a truncated rendering.
   Publication callers validate bounded portable input before scanning.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} present? [pattern value]
  (boolean
    (or (some->> (meta value) (present? pattern))
        (cond
          (or (string? value) (keyword? value) (symbol? value)) (re-find pattern (str value))
          (coll? value) (some (partial present? pattern) value)
          :else false))))

(comment
  (present? #"example" {:value "example"}))
