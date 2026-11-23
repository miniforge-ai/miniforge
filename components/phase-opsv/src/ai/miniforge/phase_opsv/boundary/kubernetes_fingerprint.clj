;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-fingerprint
  "Correlate separately admitted resources without repeating their structural schemas."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-admission :as admission]
            [ai.miniforge.phase-opsv.kubernetes-list-model :as lists]
            [ai.miniforge.phase-opsv.kubernetes-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-snapshot) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admit-consistency [value]
  (if (admission/consistent? value) value (invalid)))

(defn- ^{:stratum 1} fingerprint [value]
  (let [result (snapshot/fingerprint value)]
    (if (anomaly/anomaly? result) (invalid) result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} from-admitted [value]
  (let [typed (lists/typed-snapshot value)]
    (anomaly/let-ok [consistent (admit-consistency typed)]
      (fingerprint consistent))))

(comment ::cross-resource-external-observation-correlation)
