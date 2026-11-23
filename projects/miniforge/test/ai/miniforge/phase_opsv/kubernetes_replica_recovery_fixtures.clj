;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-recovery-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.kubernetes-verification-fixtures :as native]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} after-trial [routes]
  (-> routes
      (assoc-in [inventory/deployment-path "spec" "replicas"] fixtures/trial-count)
      (assoc-in [inventory/deployment-path "metadata" "resourceVersion"] fixtures/fresh-revision)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} input []
  (let [original (fixtures/input)
        [capture _calls] (native/capture! (after-trial (:routes original)) {})
        parameters (fixtures/parameters :restore-baseline)]
    (assoc original :current capture :parameters parameters)))

(comment ::original-expectation-and-fresh-current-capture-remain-independent)
