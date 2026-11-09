;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-list-reads
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-resource-selection :as selection]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} read! [settings until [path list-schema]]
  (let [params (cond-> {:limit (:kubernetes/max-resources settings)}
                 (contains? settings :kubernetes/pod-selector)
                 (assoc :labelSelector (:kubernetes/pod-selector settings)))
        value (http/read-resource! settings until path params)]
    (cond
      (anomaly/anomaly? value) value
      (selection/valid? settings list-schema value) value
      :else (response/invalid-response))))

(comment ::one-complete-native-collection-under-the-remaining-object-budget)
