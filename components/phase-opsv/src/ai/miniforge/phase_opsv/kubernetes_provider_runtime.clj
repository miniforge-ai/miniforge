;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-provider-runtime)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} from-input [ctx input]
  {:context ctx
   :input input})

(comment ::provider-reconstruction-uses-the-current-context-and-original-closed-input)
