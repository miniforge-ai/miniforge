;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-failure
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} mismatch [reason expected observed]
  (anomaly/anomaly :conflict (msg/ts :verification/environment-contract-mismatch)
                   {:comparison/reason reason
                    :comparison/expected expected
                    :comparison/observed observed}))

(comment ::comparison-conflict-constructor)
