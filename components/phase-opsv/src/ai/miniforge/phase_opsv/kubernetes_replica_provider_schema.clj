;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-provider-schema
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.phase-opsv.kubernetes-replica-wire-schema :as wire]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Proposal
  [:and grant/OpsvExperimentScope
   [:map [:kubernetes/request wire/Request]
    [:opsv/experiment [:map [:opsv/operation [:enum :trial-apply :restore-baseline]]
                       [:kubernetes/deployment-name names/ResourceName]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Committing
  [:and native/Committing
   [:map [:effect/class [:= :effect/deploy]] [:effect/proposal Proposal]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple native/Settings Committing fn?])

(comment ::record-shape-is-not-current-grant-or-emergency-stop-proof)
