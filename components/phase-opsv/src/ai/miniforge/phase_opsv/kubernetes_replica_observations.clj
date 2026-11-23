;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-observations
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.kubernetes-replica-configuration :as configuration]
            [ai.miniforge.phase-opsv.kubernetes-replica-images :as images]
            [ai.miniforge.phase-opsv.kubernetes-replica-placement :as placement]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} replica [target context pod]
  (when-let [projected (configuration/project (:pool-label context) pod)]
    (anomaly/let-ok [digest (artifact/content-digest projected)]
      {:observation {:replica/id (resource/uid pod)
                     :replica/target target
                     :replica/template-hash digest
                     :replica/node-pool (placement/pool context pod)
                     :replica/image-digests (images/digests pod)}
       :configuration projected})))

(defn- ^{:stratum 0} configuration-entry [pair]
  [(get-in pair [:observation :replica/id]) (:configuration pair)])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} pairs [target context pods]
  (mapv (partial replica target context) pods))

(defn- ^{:stratum 1} assembled [input membership pairs]
  (or (first (filter anomaly/anomaly? pairs))
      (when (every? some? pairs)
        {:kubernetes/source input
         :kubernetes/membership membership
         :comparison/replica-observations (mapv :observation pairs)
         :kubernetes/replica-configurations (into {} (map configuration-entry) pairs)})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [input membership]
  (let [context (placement/context input)
        pods (sort-by resource/uid (get-in membership [:kubernetes/pods "items"]))
        target (:runtime/target membership)]
    (when (placement/consistent? input context pods)
      (assembled input membership (pairs target context pods)))))
