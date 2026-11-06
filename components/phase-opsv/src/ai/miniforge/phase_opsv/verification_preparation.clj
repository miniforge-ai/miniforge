;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-preparation
  (:require [ai.miniforge.phase-opsv.boundary.verification-receipt :as receipts]
            [ai.miniforge.phase-opsv.verification-preparation-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} receipt [request observations]
  (let [result (receipts/correlate request observations)]
    (receipts/admit schema/Receipt :verification/preparation-invalid request result)))

(comment ::correlation-does-not-establish-freshness-or-authority)
