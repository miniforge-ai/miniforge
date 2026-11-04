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
            [ai.miniforge.phase-opsv.kubernetes-pod-selection :as selection]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read-list!
  "Read one complete bounded selection under a component-owned native list schema."
  [settings until list-schema]
  (let [path (requests/pods-path (:kubernetes/namespace settings))
        params {:labelSelector (:kubernetes/pod-selector settings)
                :limit (:kubernetes/max-resources settings)}
        value (http/read-resource! settings until path params)]
    (cond
      (anomaly/anomaly? value) value
      (selection/valid? settings list-schema value) value
      :else (response/invalid-response))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} read! [settings until]
  (read-list! settings until dependencies/PodList))

(comment ::complete-target-pod-selection)
