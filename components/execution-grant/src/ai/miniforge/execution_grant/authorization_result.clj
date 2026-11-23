;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.authorization-result)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} scope-mismatch [grant actual]
  {:grant/outcome :scope-mismatch
   :grant/id (:grant/id grant)
   :grant/scope (:grant/scope grant)
   :effect/scope actual})

(comment ::one-authorization-result-shape)
