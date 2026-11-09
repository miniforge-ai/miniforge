;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-schema
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.kubernetes-settings-schema :as settings]
            [ai.miniforge.schema.interface :as schema]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Settings
  (-> settings/NamespaceInventorySettings
      (mu/dissoc :kubernetes/max-resources)
      (mu/assoc :kubernetes/context schema/NonBlankString)))

(def ^{:stratum 0} Committing
  [:and effect/EffectTransaction
   [:map [:effect/authority [:= :granted]] [:effect/grant-id :uuid]
    [:effect/envelope-id :uuid] [:effect/state [:= :committing]]]])

(comment ::shared-native-provider-boundary-vocabulary)
