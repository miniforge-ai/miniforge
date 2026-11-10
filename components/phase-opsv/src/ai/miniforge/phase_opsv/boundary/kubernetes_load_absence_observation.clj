;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-absence-observation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-http :as http]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-root-bound-read :as target]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership :as ownership]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} unresolved [diagnostic]
  {:load/resource-state :unresolved :load/diagnostic diagnostic})

(defn- ^{:stratum 0} state [record pod]
  (cond
    (= response/not-found pod) :absent
    (ownership/observed? record pod) :present
    :else :unresolved))

(defn- ^{:stratum 0} read-pod! [settings path until]
  (http/read-resource! settings until path nil))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resource-observation [record resource pod]
  (let [resource-state (state record pod)]
    {:load/resource-state resource-state
     :kubernetes/resource resource}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe! [settings record roots]
  (let [resource (get-in record [:effect/observed :kubernetes/resource])
        path (str (get-in record [:effect/proposal :kubernetes/request :path]) "/" (:name resource))
        observed (anomaly/let-ok [pod (target/read! settings roots (partial read-pod! settings path))]
                   (resource-observation record resource pod))]
    (if (anomaly/anomaly? observed) (unresolved observed) observed)))

(comment
  (unresolved {:anomaly/type :unavailable}))
