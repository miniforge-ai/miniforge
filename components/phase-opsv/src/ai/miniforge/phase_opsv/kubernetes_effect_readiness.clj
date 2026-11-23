;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-readiness)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} admitted [ctx decision reference]
  {:context ctx
   :decision decision
   :recovery/plan (:recovery/plan reference)
   :recovery/hash (:recovery/hash reference)})

(comment ::shared-current-context-decision-and-retained-plan-vocabulary)
