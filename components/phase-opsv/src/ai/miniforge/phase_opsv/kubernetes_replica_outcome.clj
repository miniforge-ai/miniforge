;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-outcome
  (:require [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replica]
            [ai.miniforge.phase-opsv.kubernetes-effect-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-replica-payload :as wire]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-deployment? (m/validator replica/Deployment))

(defn- ^{:stratum 0} failure [outcome message]
  {:effect/outcome outcome :effect/failure (msg/ts message)})

(defn- ^{:stratum 0} observed [deployment]
  {:kubernetes/resource (wire/resource-reference deployment)
   :kubernetes/resource-version (get-in deployment ["metadata" "resourceVersion"])
   :replicas/count (get-in deployment ["spec" "replicas"])})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unconfirmed [] (failure :unknown-outcome :replicas/unconfirmed))

(defn ^{:stratum 1} refused [] (failure :failed :replicas/submission-refused))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} report [record deployment]
  (let [desired (get (last (:body (binding/request record))) "value")]
    (if (and (valid-deployment? deployment)
             (= (binding/reference record) (wire/resource-reference deployment))
             (= desired (get-in deployment ["spec" "replicas"])))
      {:effect/outcome :succeeded :effect/observed (observed deployment)}
      (unconfirmed))))

(comment ::provider-confirmation-is-not-readiness-or-verified-baseline-recovery)
