;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-snapshot
  "Admit captured observations; never claim live freshness or execution authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-admission :as admission]
            [ai.miniforge.phase-opsv.kubernetes-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.kubernetes-snapshot-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-snapshot) {}))

(defn- ^{:stratum 0} admitted? [value]
  (and (m/validate schema/Snapshot value) (admission/consistent? value)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fingerprint [value]
  (let [result (snapshot/fingerprint value)]
    (if (anomaly/anomaly? result) (invalid) result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} decode [value]
  (if (admitted? value) (fingerprint value) (invalid)))

(comment ::captured-observation-boundary)
