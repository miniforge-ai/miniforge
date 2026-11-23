;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-environment-fingerprint
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-controller-configuration :as controllers]
            [ai.miniforge.phase-opsv.boundary.kubernetes-outside :as outside]
            [ai.miniforge.phase-opsv.boundary.kubernetes-replicas :as replicas]
            [ai.miniforge.phase-opsv.environment-fingerprint-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-environment-fingerprint :as fingerprint]
            [ai.miniforge.phase-opsv.kubernetes-environment-lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.kubernetes-metadata-schema :as metadata]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} conflict []
  (anomaly/anomaly :conflict (msg/ts :kubernetes/incomplete-environment-fingerprint) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} complete [result]
  (if (m/validate schema/Fingerprint (:environment-fingerprint result)) result (conflict)))

(defn- ^{:stratum 1} admitted-lifecycle [replicas]
  (let [pods (get-in replicas [:kubernetes/membership :kubernetes/pods "items"])]
    (if (m/validate [:vector [:map ["metadata" metadata/Metadata]]] pods) replicas (conflict))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [capture bounds]
  (anomaly/let-ok [outside (outside/project capture)
                  controllers (controllers/project {:kubernetes/inventory (:kubernetes/inventory capture)
                                                     :comparison/bounds bounds})
                  replicas (replicas/project (select-keys capture [:kubernetes/inventory :kubernetes/nodes
                                                                   :kubernetes/node-pool-label]))
                  replicas (admitted-lifecycle replicas)
                  lifecycles (lifecycle/project replicas)]
    (complete (fingerprint/project capture outside controllers replicas lifecycles))))

(comment (project nil nil))
