;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-ownership-schema
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-schema :as load]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as plan]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} OwnershipRuntime
  [:map {:closed true} [:effects-directory schema/NonBlankString] [:provider :map]])

(def ^{:stratum 0} Pod
  [:map ["apiVersion" [:= "v1"]] ["kind" [:= "Pod"]]
   ["metadata" [:map ["name" schema/NonBlankString] ["namespace" schema/NonBlankString]
                ["uid" schema/NonBlankString] ["resourceVersion" schema/NonBlankString]]]])

(def ^{:stratum 0} Creation
  [:and effect/EffectTransaction
   [:map [:effect/class [:= :effect/load-test]] [:effect/grant-id :uuid]
    [:effect/envelope-id :uuid] [:effect/authority [:= :granted]] [:effect/state [:= :succeeded]]
    [:effect/matched? {:optional true} [:maybe [:= true]]]
    [:effect/proposal load/Proposal]
    [:effect/observed [:map {:closed true}
                       [:kubernetes/resource (mu/assoc plan/Resource :uid schema/NonBlankString)]
                       [:kubernetes/resource-version schema/NonBlankString]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple OwnershipRuntime plan/Plan])

(comment ::generic-reconciliation-is-not-a-native-creation-receipt-or-effect-permission)
