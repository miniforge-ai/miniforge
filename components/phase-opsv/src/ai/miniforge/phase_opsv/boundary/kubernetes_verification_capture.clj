;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-verification-capture
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-capture :as capture]
            [ai.miniforge.phase-opsv.boundary.kubernetes-inventory :as inventory]
            [ai.miniforge.phase-opsv.boundary.kubernetes-list-reads :as lists]
            [ai.miniforge.phase-opsv.boundary.kubernetes-membership :as membership]
            [ai.miniforge.phase-opsv.boundary.kubernetes-resource-reads :as reads]
            [ai.miniforge.phase-opsv.boundary.kubernetes-response :as response]
            [ai.miniforge.phase-opsv.kubernetes-api :as api]
            [ai.miniforge.phase-opsv.kubernetes-inventory-observation :as observation]
            [ai.miniforge.phase-opsv.kubernetes-list-schema :as list-schema]
            [ai.miniforge.phase-opsv.kubernetes-capture-plan :as requests]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as read-plan]
            [ai.miniforge.phase-opsv.kubernetes-verification-plan :as plan]
            [ai.miniforge.phase-opsv.kubernetes-verification-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private nodes-path
  "Complete cluster Node inventory; never a label-selected member view."
  (str api/core-api-prefix "/nodes"))

(defn- ^{:stratum 0} cluster! [settings until]
  (reads/read! settings until (read-plan/request "Namespace" nil false api/cluster-identity-namespace)))

(defn- ^{:stratum 0} original-inventory! [declaration settings until]
  (anomaly/let-ok [captured (inventory/capture-with-roots! declaration settings until)]
    (or (observation/original captured) (membership/mismatch))))

(defn- ^{:stratum 0} dependency-plan [captured]
  (let [pods (plan/dependency-pods captured)]
    (if (m/validate schema/DependencyPods pods)
      (requests/configuration-requests pods)
      (response/invalid-response))))

(defn- ^{:stratum 0} admit-budget [settings planned]
  (if (<= (count planned) (:kubernetes/max-resources settings)) planned (response/invalid-response)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} confirm! [declaration settings until captured cluster]
  (anomaly/let-ok [confirmed (inventory/roots declaration settings until)
                  observed-cluster (cluster! settings until)]
    (if (and (observation/roots-unchanged? captured confirmed) (= cluster observed-cluster))
      captured
      (membership/mismatch))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} capture! [declaration settings until]
  (anomaly/let-ok [captured (original-inventory! declaration settings until)
                  remaining (inventory/reserve settings (plan/used-resources captured))
                  planned (dependency-plan captured)
                  cluster (cluster! settings until)
                  nodes (lists/read! (dissoc remaining :kubernetes/namespace) until [nodes-path list-schema/CompleteNodeList])
                  remaining (update remaining :kubernetes/max-resources - (count (get nodes "items")))
                  planned (admit-budget remaining planned)
                  configuration (capture/read-many! settings until planned)
                  confirmed (confirm! declaration settings until captured cluster)]
    {:kubernetes/inventory confirmed
     :kubernetes/cluster cluster
     :kubernetes/nodes nodes
     :kubernetes/node-pool-label (:kubernetes/node-pool-label settings)
     :kubernetes/configuration configuration}))
