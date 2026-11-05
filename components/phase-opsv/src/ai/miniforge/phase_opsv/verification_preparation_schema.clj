;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-preparation-schema
  (:require [ai.miniforge.phase-opsv.environment-fingerprint-schema :as fingerprint]
            [ai.miniforge.phase-opsv.environment-target-schema :as target]
            [ai.miniforge.phase-opsv.replica-comparison-schema :as replicas]
            [ai.miniforge.phase-opsv.verification-schema :as verification]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} RawObservations [:and [:map-of :keyword :any] [:fn seq]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Observation
  [:map {:closed true}
   [:environment-fingerprint fingerprint/Fingerprint]
   [:runtime/target target/Target]
   [:environment/raw-observations {:optional true} RawObservations]
   [:comparison/replica-observations {:optional true} [:vector replicas/Observation]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Receipt (mu/merge verification/Identity Observation))

(comment ::read-only-observation-not-target-admission)
