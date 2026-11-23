;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-capture
  "Capture only the explicit target and its validated configuration dependencies."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-pods :as pods]
            [ai.miniforge.phase-opsv.boundary.kubernetes-resource-reads :as reads]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.boundary.kubernetes-fingerprint :as fingerprint]
            [ai.miniforge.phase-opsv.kubernetes-api :as api]
            [ai.miniforge.phase-opsv.kubernetes-capture-plan :as plan]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as requests]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} admit-plan [settings pods]
  (let [planned (plan/create (get pods "items"))]
    (if (<= (:plan/resource-count planned) (:kubernetes/max-resources settings))
      planned
      (response/invalid-response))))

(defn ^{:stratum 0} read-many! [settings until planned]
  (into []
        (comp (map (partial reads/read! settings until))
              (halt-when anomaly/anomaly?)
              (remove #{response/not-found}))
        planned))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} capture! [settings until]
  (anomaly/let-ok [pods (pods/read! settings until)
                   planned (admit-plan settings pods)
                   cluster (reads/read! settings until (requests/request "Namespace" nil false api/cluster-identity-namespace))
                   namespace (reads/read! settings until (requests/request "Namespace" nil false (:kubernetes/namespace settings)))
                   nodes (read-many! settings until (:plan/nodes planned))
                   configuration (read-many! settings until (:plan/configuration planned))]
    (let [node-list (requests/node-list nodes)]
      (fingerprint/from-admitted
       {:kubernetes/cluster-id (:kubernetes/cluster-id settings)
        :kubernetes/cluster cluster
        :kubernetes/namespace namespace
        :kubernetes/node-pool-label (:kubernetes/node-pool-label settings)
        :kubernetes/nodes node-list
        :kubernetes/pods pods
        :kubernetes/configuration configuration}))))

(comment ::explicit-read-only-target-capture)
