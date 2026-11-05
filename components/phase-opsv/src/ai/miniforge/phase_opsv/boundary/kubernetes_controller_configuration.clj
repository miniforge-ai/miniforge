;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-controller-configuration
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-controller-configuration :as configuration]
            [ai.miniforge.phase-opsv.kubernetes-controller-configuration-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-inventory-partition :as partition]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-controller-configuration) {}))

(defn- ^{:stratum 0} conflict []
  (anomaly/anomaly :conflict (msg/ts :kubernetes/controller-configuration-mismatch) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admitted [input]
  (if (m/validate schema/Input input) input (invalid)))

(defn- ^{:stratum 1} membership [input]
  (get (partition/project (:kubernetes/inventory input)) :kubernetes/membership (conflict)))

(defn- ^{:stratum 1} controllers [membership]
  (let [values (into [(:kubernetes/deployment membership)] (:kubernetes/replica-sets membership))]
    (if (m/validate schema/NativeScales values) values (invalid))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [input]
  (anomaly/let-ok [input (admitted input)
                  selected (membership input)
                  controlled (controllers selected)]
    (or (configuration/project input selected controlled) (conflict))))
