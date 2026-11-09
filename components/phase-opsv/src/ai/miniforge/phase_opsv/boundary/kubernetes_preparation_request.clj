;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-preparation-request
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as preparation]
            [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :verification/invalid-environment-contract) {}))

(defn- ^{:stratum 0} mismatch []
  (anomaly/anomaly :conflict (msg/ts :verification/mismatched) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} admit [declaration request]
  (anomaly/let-ok [_finite (reporting/admit [declaration request])]
    (cond
      (not (and (m/validate schema/Declaration declaration) (m/validate schema/Request request))) (invalid)
      (not (preparation/correlated? declaration request)) (mismatch)
      :else (preparation/capture-context declaration request))))

(comment (admit nil nil))
