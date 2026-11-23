;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-target
  "Original physical cluster and namespace identity at a native load boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-resource-reads :as reads]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-api :as api]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as requests]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-namespace? (m/validator schema/Namespace))

(defn- ^{:stratum 0} read-same! [settings until expected]
  (anomaly/let-ok [value (reads/read! settings until
                                   (requests/request "Namespace" nil false (resource/name-of expected)))]
    (if (= (resource/uid expected) (resource/uid value)) value (response/invalid-response))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} original [baseline target]
  (let [source (get-in baseline [:environment/raw-observations :kubernetes/verification :kubernetes/source])
        cluster (:kubernetes/cluster source)
        namespace (get-in source [:kubernetes/inventory :kubernetes/namespace])]
    (if (and (valid-namespace? cluster) (valid-namespace? namespace)
             (= api/cluster-identity-namespace (resource/name-of cluster))
             (= (:namespace target) (resource/name-of namespace))
             (= (:namespace-uid target) (resource/uid namespace)))
      [cluster namespace]
      (response/invalid-response))))

(defn ^{:stratum 1} observe! [settings until [cluster namespace]]
  (anomaly/let-ok [current-cluster (read-same! settings until cluster)
                  current-namespace (read-same! settings until namespace)]
    {:kubernetes/cluster-uid (resource/uid current-cluster)
     :kubernetes/namespace-uid (resource/uid current-namespace)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} confirm! [settings until roots]
  (anomaly/let-ok [_observed (observe! settings until roots)] true))

(comment
  (original {} {}))
