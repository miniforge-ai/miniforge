;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-schema :as restoration]
            [ai.miniforge.phase-opsv.kubernetes-replica-provider-schema :as replica]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} RestorationRuntime
  [:map {:closed true} [:context retained/Context] [:input restoration/Input]])

(def ^{:stratum 0} Committing
  [:and replica/Committing
   [:map [:effect/proposal [:map [:opsv/experiment [:map [:opsv/operation [:= :restore-baseline]]]]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple RestorationRuntime Committing fn?])

(comment
  (count Arguments))
