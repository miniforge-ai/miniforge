;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-snapshot
  "Admit captured observations; never claim live freshness or execution authority."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-fingerprint :as fingerprint]
            [ai.miniforge.phase-opsv.kubernetes-snapshot-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} decode [value]
  (if (m/validate schema/Snapshot value)
    (fingerprint/from-admitted value)
    (fingerprint/invalid)))

(comment ::captured-observation-boundary)
