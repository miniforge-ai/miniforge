;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup-outcome
  "Confirm only native deletion acknowledgement, never absence or recovered baseline."
  (:require [ai.miniforge.phase-opsv.kubernetes-effect-binding :as binding]
            [ai.miniforge.phase-opsv.kubernetes-effect-outcome :as outcome]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup-observation-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-schema :as ownership]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-pod? (m/validator ownership/Pod))

(def ^{:stratum 0 :private true} valid-status? (m/validator schema/Status))

(defn ^{:stratum 0} unconfirmed []
  (outcome/failure :unknown-outcome :load/cleanup-unconfirmed))

(defn ^{:stratum 0} refused []
  (outcome/failure :failed :load/cleanup-submission-refused))

(defn- ^{:stratum 0} observation [record response]
  (let [resource (binding/reference record)
        revision (get-in response ["metadata" "resourceVersion"])
        observed {:kubernetes/resource resource
                  :load/deletion-acknowledged? true}]
    (cond-> observed (some? revision) (assoc :kubernetes/resource-version revision))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} confirmed? [record response]
  (cond
    (valid-pod? response) (binding/same-pod? record response)
    (valid-status? response) (binding/same-status? record response)
    :else false))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} report [record response]
  (if (confirmed? record response)
    (outcome/acknowledged (observation record response))
    (unconfirmed)))

(comment ::the-runtime-must-observe-absence-after-the-exact-acknowledged-effect-separately)
