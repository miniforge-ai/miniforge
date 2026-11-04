;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-environment-schema
  (:require [ai.miniforge.phase-opsv.environment-comparison-schema :as comparison]
            [ai.miniforge.phase-opsv.replica-comparison-schema :as replicas]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Declaration
  (mu/merge
    (mu/dissoc (mu/dissoc comparison/Binding :workflow/id) :verification/id)
    [:map {:closed true}
     [:comparison/changes comparison/Changes]
     [:comparison/replica-contract {:optional true} replicas/Contract]]))

(comment ::host-intent-never-supplies-the-original-baseline-or-attempt-id)
