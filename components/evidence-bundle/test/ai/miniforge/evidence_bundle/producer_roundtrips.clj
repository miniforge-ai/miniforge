;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.producer-roundtrips
  "Assemble live producer shapes for projection and validation contract tests."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.collector :as collector]
            [ai.miniforge.evidence-bundle.producer-fixtures :as fixtures]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unsealed-bundle [state]
  (dissoc (collector/assemble-evidence-bundle (random-uuid) state nil) :evidence/content-hash))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} collected-bundles []
  (mapv (comp unsealed-bundle fixtures/producer-state)
        [nil (anomaly/anomaly :fault "Failure" {})
         (response/make-anomaly :anomalies/fault "Failure")
         {:message "Failure" :phase nil}]))
