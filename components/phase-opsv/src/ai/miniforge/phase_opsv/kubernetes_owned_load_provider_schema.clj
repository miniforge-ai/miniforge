;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-owned-load-provider-schema
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.kubernetes-effect-schema :as native]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-schema :as request]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} OwnedLoadRuntime
  [:map {:closed true} [:context retained/Context] [:input request/Input]])

(defn ^{:stratum 0} proposal [operation method media-type body-schema]
  [:and grant/OpsvExperimentScope
   [:map [:effect/class [:= :effect/load-test]]
    [:opsv/experiment [:map [:opsv/operation [:= operation]]]]
    [:kubernetes/request [:map {:closed true} [:method [:= method]]
                          [:path schema/NonBlankString] [:media-type [:= media-type]]
                          [:body body-schema]]]]])

(defn ^{:stratum 0} committing [proposal]
  [:and native/Committing [:map [:effect/class [:= :effect/load-test]] [:effect/proposal proposal]]])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} arguments [proposal]
  [:tuple OwnedLoadRuntime (committing proposal) fn?])

(comment
  (proposal :start-load :patch "application/json-patch+json" [:vector :map]))
