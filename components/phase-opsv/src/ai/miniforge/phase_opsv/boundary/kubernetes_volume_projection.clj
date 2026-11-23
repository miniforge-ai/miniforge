;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-volume-projection
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-volume-bindings :as bindings]
            [ai.miniforge.phase-opsv.kubernetes-volume-projection :as projection]
            [ai.miniforge.phase-opsv.kubernetes-volume-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-volume-projection) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admitted [spec]
  (if-let [plan (bindings/plan spec)]
    (projection/project plan spec)
    (invalid)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [spec]
  (if (m/validate schema/PodSpec spec) (admitted spec) (invalid)))

(comment ::one-input-shape-boundary-no-experiment-or-mutation-authority)
