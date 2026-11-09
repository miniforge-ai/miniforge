;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-provider-schema
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-schema :as stop]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} StopRuntime
  [:map {:closed true} [:context retained/Context] [:input stop/Input]])

(def ^{:stratum 0} Proposal
  [:and grant/OpsvExperimentScope
   [:map [:effect/class [:= :effect/load-test]]
    [:opsv/experiment [:map [:opsv/operation [:= :stop-load]]]]
    [:kubernetes/request [:map {:closed true} [:method [:= :patch]]
                          [:path schema/NonBlankString] [:media-type [:= "application/json-patch+json"]]
                          [:body [:vector :map]]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Committing
  [:and native/Committing [:map [:effect/class [:= :effect/load-test]] [:effect/proposal Proposal]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple StopRuntime Committing fn?])

(comment ::structural-shape-is-not-current-authority-or-canonical-request-proof)
