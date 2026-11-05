;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-expectation)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} fingerprint [contract stage]
  (let [baseline (:comparison/baseline contract)]
    (case stage
      :candidate (merge baseline (:comparison/changes contract))
      :rollback baseline)))

(comment ::one-authority-for-declared-environment-expectations)
