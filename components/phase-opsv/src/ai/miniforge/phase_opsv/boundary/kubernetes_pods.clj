;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-pods
  "Admit one complete explicit Pod selection before deriving any further reads."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-dependency-schema :as dependencies]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as requests]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} target-pod? [namespace pod]
  (= namespace (resource/namespace-of pod)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admit [settings value]
  (let [namespace (:kubernetes/namespace settings)
        items (get value "items")]
    (cond
      (anomaly/anomaly? value) value
      (not (m/validate dependencies/PodList value)) (response/invalid-response)
      (seq (get-in value ["metadata" "continue"])) (response/invalid-response)
      (> (count items) (:kubernetes/max-resources settings)) (response/invalid-response)
      (not (every? (partial target-pod? namespace) items)) (response/invalid-response)
      :else value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read! [settings until]
  (let [path (requests/pods-path (:kubernetes/namespace settings))
        params {:labelSelector (:kubernetes/pod-selector settings)
                :limit (:kubernetes/max-resources settings)}
        value (http/read-resource! settings until path params)]
    (admit settings value)))

(comment ::complete-target-pod-selection)
