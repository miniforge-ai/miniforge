;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.kubernetes-target.fixtures)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} target []
  {:cluster "staging"
   :namespace "catalog"
   :namespace-uid "namespace-uid"
   :workload-uid "deployment-uid"})

(comment ::mirrors-phase-opsv-kubernetes-membership-fixtures-target)
