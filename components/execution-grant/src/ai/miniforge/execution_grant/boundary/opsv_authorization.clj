;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.boundary.opsv-authorization
  (:require [ai.miniforge.execution-grant.authorization-result :as result]
            [ai.miniforge.execution-grant.constraints :as constraints]
            [ai.miniforge.execution-grant.opsv-profile :as profile]
            [ai.miniforge.execution-grant.opsv-scope-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-scope? (m/validator schema/Scope))

(defn- ^{:stratum 0} profile-selected? [grant actual]
  (let [class (:grant/effect-class grant)]
    (or (schema/experiment-effect? class (:grant/scope grant))
        (schema/experiment-effect? class actual))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admitted? [grant actual]
  (let [required (:grant/scope grant)]
    (and (valid-scope? required)
         (valid-scope? actual)
         (false? (:grant/delegable? grant))
         (nil? (:grant/parent-id grant))
         (= 1 (get-in grant [:grant/constraints :constraint/max-count]))
         (profile/admissible? (:grant/effect-class grant) actual))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} authorize
  ([grant usage now] (authorize grant usage now nil))
  ([grant usage now lookup]
   (let [actual (:effect/scope usage)]
     (if (and (some? grant) (profile-selected? grant actual) (not (admitted? grant actual)))
       (result/scope-mismatch grant actual)
       (constraints/authorize grant usage now lookup)))))

(comment ::generic-deployment-authority-cannot-become-trial-authority)
