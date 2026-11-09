;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration
  "Compose the existing original-baseline constructor with a current recovery plan and native capture."
  (:require [ai.miniforge.phase-opsv.kubernetes-replica-proposal :as proposal]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} restore-parameters {:opsv/operation :restore-baseline})

(defn ^{:stratum 0} configured-for-plan? [input plan]
  (proposal/configured-for-plan? (:restoration/provider input) (:effect/id input) plan))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} proposal-input [input plan capture]
  (proposal/proposal-input
    (assoc-in input [:recovery/input :load :limits :opsv/duration-ms] (:recovery/duration-ms plan))
    capture restore-parameters))

(comment
  (configured-for-plan? {} {})
  (proposal-input {:recovery/input {:load {:limits {}}}} {:recovery/duration-ms 1000} {}))
