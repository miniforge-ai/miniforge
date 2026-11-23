;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-execution
  "API surface class 1: one governed staging candidate effect, not whole-trial success."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-forward-execution :as execution]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-authority :as authority]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-dispatch :as dispatch]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial :as proposal]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replica-trial-effect :as native]
            [ai.miniforge.phase-opsv.kubernetes-forward-operation :as kind]
            [ai.miniforge.phase-opsv.kubernetes-replica-trial-schema :as input]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare! [ready input]
  (proposal/prepare! (:context ready) input))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1 :private true} operation
  (kind/definition input/Input
    :effect-id :effect/id
    :readiness admission/readiness!
    :prepare prepare!
    :native native/patch!
    :issue authority/issue!
    :invoke dispatch/invoke!
    :material [:native-replica-trial-readiness :opsv-verification-report :opsv/native-replica-trial-readiness]
    :observation-key :trial/observation
    :material-observation-key :comparison/observed))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} apply! [runtime input] (execution/execute-forward! operation runtime input))

(comment
  (apply! {} {}))
