;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-recovery-identity
  "API surface class 1: stable recovery slots, never execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.kubernetes-recovery-effect :as identity]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as plan]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments?
  (m/validator [:tuple opsv/PolicyHash (into [:enum] plan/operations)]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} identity-for [digest operation]
  (if (valid-arguments? [digest operation])
    (identity/identity-for digest operation)
    (anomaly/anomaly :invalid-input (msg/ts :recovery/invalid-effect-identity) {})))

(comment
  (identity-for nil :restore-baseline))
