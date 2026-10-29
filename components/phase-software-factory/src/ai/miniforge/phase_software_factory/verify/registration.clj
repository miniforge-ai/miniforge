;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.verify.registration
  "Register the verify lifecycle while preserving dynamic Var seams."
  (:require [ai.miniforge.phase.interface :as phase]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} enter-configured [enter config ctx]
  (enter (assoc ctx :phase-config config)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} interceptor [id handlers config]
  (let [merged (phase/merge-with-defaults config)
        enter (partial enter-configured (:enter handlers) merged)
        leave @(:leave handlers)
        error @(:error handlers)]
    {:name id :config merged :enter enter :leave leave :error error}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} register! [id defaults handlers]
  (defmethod phase/get-phase-interceptor-method :verify [config]
    (interceptor id handlers config))
  (phase/register-phase-defaults! :verify defaults))

(comment ::verify-registration)
