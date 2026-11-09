;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-audit-schema
  (:require [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-schema :as creation]
            [ai.miniforge.phase-opsv.kubernetes-load-start-provider-schema :as start]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-provider-schema :as stop]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-provider-schema :as cleanup]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-schema :as replica]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NativeRecord
  [:and effect/EffectTransaction
   [:map [:effect/class [:enum :effect/deploy :effect/load-test]]
    [:effect/authority {:optional true} [:= :granted]] [:effect/grant-id :uuid] [:effect/envelope-id :uuid]
    [:effect/observed {:optional true} [:maybe :map]]
    [:effect/proposal [:or creation/Proposal start/Proposal stop/Proposal cleanup/Proposal replica/Proposal]]]])

(def ^{:stratum 0} Arguments
  [:tuple retained/Context schema/NonBlankString :uuid decision/AllowingEnvelope])

(def ^{:stratum 0} material
  [:native-effect-disposition :opsv-verification-report :opsv/native-effect-disposition])

(def ^{:stratum 0} observed-keys
  [:kubernetes/resource :kubernetes/resource-version :replicas/count :load/deadline-seconds
   :load/deletion-acknowledged? :load/gate-release-acknowledged?])

(comment
  (count Arguments))
