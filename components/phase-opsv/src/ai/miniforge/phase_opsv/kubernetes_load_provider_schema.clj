;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-provider-schema
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Proposal
  [:and grant/OpsvExperimentScope
   [:map [:effect/class [:= :effect/load-test]]
    [:opsv/experiment [:map [:opsv/operation [:= :start-load]]]]
    [:kubernetes/request [:map {:closed true} [:method [:= :post]]
                          [:path schema/NonBlankString] [:media-type [:= "application/json"]] [:body :map]]]]])

(def ^{:stratum 0} Observation
  [:map ["apiVersion" [:= "v1"]] ["kind" [:= "Pod"]]
   ["metadata" [:map ["uid" schema/NonBlankString] ["resourceVersion" schema/NonBlankString]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Committing
  [:and native/Committing
   [:map [:effect/class [:= :effect/load-test]] [:effect/proposal Proposal]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple native/Settings Committing fn?])

(comment ::structural-admission-still-requires-current-trusted-dispatch-and-canonical-binding)
