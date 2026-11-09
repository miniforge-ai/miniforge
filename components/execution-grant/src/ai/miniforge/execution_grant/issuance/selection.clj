;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.issuance.selection
  (:require [ai.miniforge.execution-grant.opsv-scope-schema :as opsv]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} profile-key [request]
  (if (opsv/experiment-effect? (:effect/class request) request)
    :opsv/experiment
    (:effect/class request)))

(comment ::a-profile-cannot-fall-back-to-generic-deployment-authority)
