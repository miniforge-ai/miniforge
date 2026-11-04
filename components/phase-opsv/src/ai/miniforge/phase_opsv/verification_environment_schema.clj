;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-environment-schema
  (:require [ai.miniforge.phase-opsv.environment-comparison-schema :as comparison]
            [ai.miniforge.phase-opsv.replica-comparison-schema :as replicas]
            [ai.miniforge.phase-opsv.verification-preparation-schema :as preparation]
            [ai.miniforge.phase-opsv.verification-schema :as verification]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Declaration
  (mu/merge
    (mu/dissoc (mu/dissoc comparison/Binding :workflow/id) :verification/id)
    [:map {:closed true}
     [:comparison/changes comparison/Changes]
     [:comparison/replica-contract {:optional true} replicas/Contract]]))

(def ^{:stratum 0} Observation
  (mu/merge preparation/Observation
    [:map {:closed true}
     [:environment/raw-observations preparation/RawObservations]]))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Receipt (mu/merge verification/Receipt Observation))

(def ^{:stratum 1} PreparationReceipt (mu/merge verification/Identity Observation))

(comment ::host-intent-never-supplies-the-original-baseline-or-attempt-id)
