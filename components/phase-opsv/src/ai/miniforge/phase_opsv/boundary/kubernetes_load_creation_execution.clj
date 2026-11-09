;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-creation-execution
  "API surface class 1: governed inert Pod creation, never gate release or whole-trial success."
  (:require [ai.miniforge.phase-opsv.boundary.kubernetes-forward-execution :as execution]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-creation-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-provider :as native]
            [ai.miniforge.phase-opsv.kubernetes-forward-runtime-schema :as runtime]
            [ai.miniforge.phase-opsv.kubernetes-load-creation-schema :as input]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} create-pod! [_ctx input record dispatch]
  (native/create! (:creation/provider input) record dispatch))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1 :private true} operation
  {:valid-arguments? (m/validator [:tuple runtime/HostRuntime input/Input])
   :effect-id input/effect-id
   :readiness admission/readiness!
   :prepare admission/prepare!
   :native create-pod!
   :material [:native-load-creation-readiness :opsv-verification-report :opsv/native-load-creation-readiness]
   :observation-key :target/observation})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create! [runtime input] (execution/execute-forward! operation runtime input))

(comment
  (create! {} {}))
