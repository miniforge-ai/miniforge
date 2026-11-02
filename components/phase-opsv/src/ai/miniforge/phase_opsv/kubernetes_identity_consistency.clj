;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-identity-consistency
  "Repeated observations must agree on identity and fingerprint material."
  (:require [ai.miniforge.phase-opsv.kubernetes-images :as images]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} logical-identity [resource]
  (pop (resource/identity-key resource)))

(defn- ^{:stratum 0} material [resource]
  [(resource/identity-key resource) (resource/configuration resource)
   (get-in resource ["status" "nodeInfo"]) (images/pod-images resource)])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} same-observation? [resources]
  (apply = (map material resources)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} consistent? [resources]
  (let [physical (vals (group-by resource/uid resources))
        logical (vals (group-by logical-identity resources))]
    (every? same-observation? (concat physical logical))))

(comment ::consistent-resource-observations)
