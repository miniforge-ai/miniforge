;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-membership
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-list-model :as lists]
            [ai.miniforge.phase-opsv.kubernetes-membership :as membership]
            [ai.miniforge.phase-opsv.kubernetes-membership-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} mismatch []
  (anomaly/anomaly :conflict (msg/ts :kubernetes/membership-mismatch) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} admit [observed]
  (cond
    (not (m/validate schema/Observation observed))
    (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-membership) {})
    (not (membership/consistent?
           (update observed :kubernetes/pods (partial lists/typed-list "Pod"))))
    (mismatch)
    :else observed))

(comment ::captured-identity-checks-not-live-acquisition-or-effect-admission)
