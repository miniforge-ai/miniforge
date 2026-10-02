;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint
  "Preserve domain values across the shared workflow's text-oriented checkpoints."
  (:require [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} persist [ctx assembly]
  (let [snapshot (snapshot/encode ctx :checkpoint assembly)]
    (-> ctx
        (assoc-in [:execution/input :opsv/evidence-assembly] assembly)
        (assoc-in [:execution/input :opsv/evidence-snapshot] snapshot))))

(defn ^{:stratum 0} restore [ctx]
  (let [input (:execution/input ctx)]
    (if-not (contains? input :opsv/evidence-snapshot)
      (:opsv/evidence-assembly input)
      (snapshot/decode ctx :checkpoint (:opsv/evidence-snapshot input)))))
