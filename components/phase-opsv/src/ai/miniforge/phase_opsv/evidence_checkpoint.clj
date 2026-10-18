;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint
  "Preserve domain values across the shared workflow's text-oriented checkpoints."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} persist [ctx assembly]
  (let [failed? (anomaly/any-anomaly? assembly)
        encoded (if failed? assembly (snapshot/encode ctx :checkpoint assembly))]
    (cond-> (assoc-in ctx [:execution/input :opsv/evidence-snapshot] encoded)
      (not failed?) (assoc-in [:execution/input :opsv/evidence-assembly] assembly))))

(defn ^{:stratum 0} restore [ctx]
  (let [input (:execution/input ctx)]
    (if-not (contains? input :opsv/evidence-snapshot)
      (:opsv/evidence-assembly input)
      (snapshot/decode ctx :checkpoint (:opsv/evidence-snapshot input)))))
