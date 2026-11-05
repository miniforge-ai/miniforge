;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-preparation-schema
  (:require [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.environment-comparison-schema :as comparison]
            [ai.miniforge.phase-opsv.environment-target-schema :as target]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as preparation]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory-schema :as inventory]
            [ai.miniforge.phase-opsv.verification-schema :as verification]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Declaration
  (mu/merge inventory/Target [:map {:closed true} [:workflow/id :uuid]]))

(def ^{:stratum 0} Pin
  [:and comparison/Pin [:fn preparation/population-declared?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Request
  (mu/merge verification/Identity
    [:map {:closed true}
     [:candidate/hash opsv/PolicyHash]
     [:experiment-pack/hash opsv/PolicyHash]
     [:candidate/policy opsv/OperationalPolicy]
     [:experiment-pack opsv/ExperimentPack]
     [:runtime/target target/Target]
     [:comparison/pin Pin]]))

(comment ::native-preparation-requires-explicit-recovery-and-candidate-membership)
