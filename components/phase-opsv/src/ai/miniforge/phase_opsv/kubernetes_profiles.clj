;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-profiles
  "Canonical resource and node profiles for admitted observations."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ordered [resources]
  (sort-by resource/identity-key resources))

(defn- ^{:stratum 0} profile [resource content]
  (let [id (resource/identity-key resource)
        digest (artifact/content-digest content)]
    (if (anomaly/anomaly? digest)
      digest
      {:resource/id id
       :resource/hash digest})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resource-profile [resource]
  (profile resource (resource/configuration resource)))

(defn- ^{:stratum 1} node-profile [label node]
  (let [desired (resource/configuration node)
        runtime (resource/observed-runtime node)
        content {:node/desired desired
                 :node/runtime runtime}
        observed (profile node content)
        pool (get-in node ["metadata" "labels" label])]
    (cond-> observed
      (not (anomaly/anomaly? observed)) (assoc :node/pool pool))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resource-profiles [resources]
  (mapv resource-profile (ordered resources)))

(defn ^{:stratum 2} node-profiles [label nodes]
  (mapv (partial node-profile label) (ordered nodes)))

(comment ::canonical-observation-profiles)
