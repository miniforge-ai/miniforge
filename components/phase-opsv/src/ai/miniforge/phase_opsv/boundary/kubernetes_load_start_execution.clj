;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-start-execution
  "API surface class 1: trusted-host orchestration of one exact governed load start."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-forward-execution :as execution]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start :as proposal]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-provider :as native]
            [ai.miniforge.phase-opsv.kubernetes-forward-operation :as kind]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-schema :as input]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare! [ready input]
  (proposal/prepare! (:context ready) input))

(defn- ^{:stratum 0} patch! [ctx input record dispatch]
  (native/patch! {:context ctx
                  :input input} record dispatch))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1 :private true} operation
  (kind/definition input/Input
    :effect-id :effect/id
    :readiness admission/readiness!
    :prepare prepare!
    :native patch!
    :material [:native-load-start-readiness :opsv-verification-report :opsv/native-load-start-readiness]
    :observation-key :ownership/observation))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start! [runtime input] (execution/execute-forward! operation runtime input))

(comment
  (start! {} {}))
