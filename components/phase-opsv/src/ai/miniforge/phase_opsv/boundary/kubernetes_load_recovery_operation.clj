;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-operation
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-load-recovery-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-authority :as authority]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-dispatch :as dispatch]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-effect-admission :as effect-admission]
            [ai.miniforge.phase-opsv.kubernetes-forward-operation :as kind]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-provider-runtime :as provider]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare! [constructor ready input]
  (constructor (:context ready) input))

(defn- ^{:stratum 0} mutate! [native ctx input record dispatch]
  (native (provider/from-input ctx input) record dispatch))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} definition [operation constructor native material]
  (kind/definition schema/Input
    :effect-id :effect/id
    :readiness (partial admission/readiness! operation)
    :prepare (partial prepare! constructor)
    :admit effect-admission/admit!
    :native (partial mutate! native)
    :issue authority/issue!
    :invoke dispatch/invoke!
    :material material
    :observation-key :recovery/effect-admission
    :material-observation-key :recovery/effect-admission))

(comment ::code-owned-compensation-vocabulary-not-a-serialized-provider-or-grant)
