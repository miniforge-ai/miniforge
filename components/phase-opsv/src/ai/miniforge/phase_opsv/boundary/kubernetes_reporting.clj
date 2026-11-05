;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-reporting
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-model :as model]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-reporting) {}))

(defn- ^{:stratum 0} failed []
  (anomaly/anomaly :unavailable (msg/ts :kubernetes/reporting-protection-failed) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admitted [value]
  (if (fields/supported? value) {:reporting/value value} (invalid)))

(defn- ^{:stratum 1} protected [prepared]
  (if (fields/classified-protected? (redaction/marker) (:artifact/content prepared)) prepared (failed)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [value]
  (anomaly/let-ok [data (admitted value)
                  prepared (evidence/prepare-artifact-content (model/reporting-copy (:reporting/value data)))]
    (protected prepared)))

(comment (prepare {"kind" "Pod"}))
