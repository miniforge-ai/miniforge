;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint
  "Preserve domain values across the shared workflow's text-oriented checkpoints."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record [ctx assembly]
  (artifact/build-artifact
   {:id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
    :type :manifest :version "1.0.0" :content assembly
    :metadata {:workflow/id (context/workflow-id ctx) :opsv/material-kind :checkpoint}}))

(defn- ^{:stratum 0} invalid [ctx]
  (anomaly/anomaly :invalid-input (msg/ts :evidence/assembly-mismatch)
                   {:opsv/evidence-bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} persist [ctx assembly]
  (let [snapshot (artifact/encode-snapshot (record ctx assembly))]
    (-> ctx
        (assoc-in [:execution/input :opsv/evidence-assembly] assembly)
        (assoc-in [:execution/input :opsv/evidence-snapshot] snapshot))))

(defn ^{:stratum 1} restore [ctx]
  (let [input (:execution/input ctx)]
    (if-not (contains? input :opsv/evidence-snapshot)
      (:opsv/evidence-assembly input)
      (let [value (artifact/decode-snapshot (:opsv/evidence-snapshot input))
            assembly (:artifact/content value)]
        (cond
          (anomaly/anomaly? value) value
          (= value (record ctx assembly)) assembly
          :else (invalid ctx))))))
