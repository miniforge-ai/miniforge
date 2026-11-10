;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-trial-comparison-reporting
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-baseline-recovery-reporting :as reporting]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} material
  [:native-replica-trial-comparison :opsv-verification-report :opsv/native-replica-trial-comparison])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} refuse! [ctx input admitted original observed]
  (anomaly/let-ok [output (reporting/publish! ctx input admitted original observed material)]
    (let [failure (get-in output [(nth material 2) :comparison/result])]
      (assoc-in failure [:anomaly/data :opsv/phase-output] output))))

(comment ::failed-trial-comparison-is-not-baseline-recovery-or-permission-to-adopt-drift)
