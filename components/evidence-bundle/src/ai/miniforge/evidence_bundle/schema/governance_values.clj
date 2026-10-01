;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.governance-values
  "Scalar contracts shared by portable knowledge and gate evidence.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sha256 [:re #"^[0-9a-fA-F]{64}$"])

(defn ^{:stratum 0} vector-of [item-schema]
  [:and vector? [:vector item-schema]])
