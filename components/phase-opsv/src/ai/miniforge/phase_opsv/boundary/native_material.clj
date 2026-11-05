;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.native-material
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.native-material-projection :as projection]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :verification/unsupported-report) {}))

(defn- ^{:stratum 0} failed []
  (anomaly/anomaly :unavailable (msg/ts :kubernetes/reporting-protection-failed) {}))

(defn- ^{:stratum 0} wrapped [value] {:reporting/value value})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} admit [value]
  (if (projection/supported? value) (wrapped value) (invalid)))

(defn ^{:stratum 1} confirm [value]
  (if (projection/protected? (redaction/marker) value) (wrapped value) (failed)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} classify [value]
  (anomaly/let-ok [admitted (admit value)] (wrapped (projection/project (:reporting/value admitted)))))

(comment (classify {:unrelated :data}))
