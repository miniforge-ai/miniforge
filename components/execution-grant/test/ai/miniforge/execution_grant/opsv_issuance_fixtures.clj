;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-issuance-fixtures
  (:require [ai.miniforge.execution-grant.opsv-scope-fixtures :as scope]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maximum-duration-ms (* 30 60 1000))

(defn ^{:stratum 0} preflight []
  {:preflight/type :preflight/opsv-staging-experiment
   :preflight/result :allow})

(defn ^{:stratum 0} breach [g]
  {:breach/id (random-uuid)
   :breach/principal (:grant/principal g)
   :breach/grant-id (:grant/id g)
   :breach/effect-class (:grant/effect-class g)
   :breach/axis :constraint/max-count
   :breach/limit 1
   :breach/observed 2
   :breach/detection :detected
   :breach/at scope/now})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} request [class operation]
  (let [binding (scope/scope operation)
        preflight (preflight)]
    (assoc binding
           :effect/class class
           :workflow-run/status :running
           :effect/preflight preflight)))

(comment ::canonical-runtime-request-and-registered-breach-shapes)
