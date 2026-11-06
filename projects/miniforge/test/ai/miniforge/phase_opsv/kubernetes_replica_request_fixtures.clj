;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures
  (:require [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-preparation-fixtures :as preparation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} trial-count "A bounded change from the native fixture's two replicas." 3)

(def ^{:stratum 0} original-count "The desired count in the independently captured original Deployment." 2)

(def ^{:stratum 0} fresh-revision "A server revision observed after the trial, not the original revision." "after-trial")

(defn ^{:stratum 0} prepare [{:keys [declaration request baseline current parameters]}]
  (phase/prepare-kubernetes-replica-request declaration request baseline current parameters))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parameters [operation]
  (cond-> {:opsv/operation operation}
    (= :trial-apply operation) (assoc :replicas/count trial-count)))

(defn- ^{:stratum 1} bounded-plan [input]
  (let [request (:request input)
        contract (get-in request [:comparison/pin :comparison/contract])
        contract (-> contract
                     (assoc-in [:comparison/replica-contract :candidate :replicas/min-count] trial-count)
                     (assoc-in [:comparison/replica-contract :candidate :replicas/max-count] (inc trial-count)))
        pin (phase/pin-verification-environment contract)]
    (assoc input :request (assoc request :comparison/pin pin))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} input []
  (let [native (bounded-plan (preparation/input))
        [baseline _calls] (preparation/observe-transport (:routes native) native)
        parameters (parameters :trial-apply)]
    (assoc native :baseline baseline :current (:capture native) :parameters parameters)))

(comment ::real-native-producer-shapes-only-http-transport-is-simulated)
