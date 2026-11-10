;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-baseline-restoration-execution
  "API surface class 1: independently governed compensation, not verified recovery."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-baseline-restoration :as proposal]
            [ai.miniforge.phase-opsv.boundary.kubernetes-baseline-restoration-provider :as native]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-execution :as execution]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-authority :as authority]
            [ai.miniforge.phase-opsv.boundary.kubernetes-recovery-dispatch :as dispatch]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-forward-operation :as kind]
            [ai.miniforge.phase-opsv.kubernetes-provider-runtime :as provider]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare! [ready input]
  (proposal/prepare! (:context ready) input))

(defn- ^{:stratum 0} restore! [ctx input record dispatch]
  (let [runtime (provider/from-input ctx input)]
    (native/restore! runtime record dispatch)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1 :private true} operation
  (kind/definition schema/Input
    :effect-id :effect/id
    :readiness admission/restoration-readiness!
    :prepare prepare!
    :native restore!
    :issue authority/issue!
    :invoke dispatch/invoke!
    :material [:native-baseline-restoration-readiness :opsv-verification-report :opsv/native-baseline-restoration-readiness]
    :observation-key :recovery/plan
    :material-observation-key :recovery/plan))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} apply! [runtime input]
  (execution/execute-forward! operation runtime input))

(comment
  (apply! {} {}))
