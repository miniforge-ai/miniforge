;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-snapshot
  "Bind lossless snapshots to one workflow, evidence identity and material kind."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record [ctx kind content]
  (artifact/build-artifact
   {:id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
    :type :manifest :version "1.0.0" :content content
    :metadata {:workflow/id (context/workflow-id ctx) :opsv/material-kind kind}}))

(defn- ^{:stratum 0} invalid [ctx]
  (anomaly/anomaly :invalid-input (msg/ts :evidence/assembly-mismatch)
                   {:opsv/evidence-bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} encode [ctx kind content]
  (artifact/encode-snapshot (record ctx kind content)))

(defn ^{:stratum 1} decode [ctx kind snapshot]
  (let [value (artifact/decode-snapshot snapshot)
        content (:artifact/content value)]
    (cond
      (anomaly/anomaly? value) value
      (= value (record ctx kind content)) content
      :else (invalid ctx))))
