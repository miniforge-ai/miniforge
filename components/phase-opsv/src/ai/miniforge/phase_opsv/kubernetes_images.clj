;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-images
  "Immutable image observations from admitted Pod status records.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} container-groups
  [["containers" "containerStatuses" :regular]
   ["initContainers" "initContainerStatuses" :init]
   ["ephemeralContainers" "ephemeralContainerStatuses" :ephemeral]])

(defn- ^{:stratum 0} container-name [container] (get container "name"))

(defn- ^{:stratum 0} image-entry [uid kind status]
  (let [name (get status "name")
        image (get status "imageID")]
    {:pod/uid uid
     :container/kind kind
     :container/name name
     :image/id image}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} image-group [pod [_spec-field status-field kind]]
  (let [uid (get-in pod ["metadata" "uid"])
        statuses (get-in pod ["status" status-field])]
    (mapv (partial image-entry uid kind) (sort-by container-name statuses))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} pod-images [pod]
  (vec (mapcat (partial image-group pod) container-groups)))

(comment ::immutable-pod-image-evidence)
