;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-provider-schema
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
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
  [:and effect/EffectTransaction
   [:map [:effect/class [:= :effect/deploy]] [:effect/authority [:= :granted]]
    [:effect/grant-id :uuid] [:effect/envelope-id :uuid]
    [:effect/state [:= :committing]] [:effect/proposal Proposal]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple wire/Settings Committing fn?])

(comment ::record-shape-is-not-current-grant-or-emergency-stop-proof)
