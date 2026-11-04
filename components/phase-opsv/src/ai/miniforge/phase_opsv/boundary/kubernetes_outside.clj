;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-outside
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-inventory-partition :as partition]
            [ai.miniforge.phase-opsv.kubernetes-api :as api]
            [ai.miniforge.phase-opsv.kubernetes-outside-inventory :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-outside-projection :as projection]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-resource-selection :as selection]
            [ai.miniforge.phase-opsv.kubernetes-reported-runtime-schema :as runtime]
            [ai.miniforge.phase-opsv.kubernetes-verification-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} conflict []
  (anomaly/anomaly :conflict (msg/ts :kubernetes/uncorrelated-outside) {}))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :kubernetes/invalid-outside) {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} admitted [input]
  (if (and (m/validate schema/Observation input)
           (= api/cluster-identity-namespace (resource/name-of (:kubernetes/cluster input)))) input
      (invalid)))

(defn- ^{:stratum 1} membership [input]
  (or (:kubernetes/membership (partition/project (:kubernetes/inventory input))) (conflict)))

(defn- ^{:stratum 1} resources [input]
  (let [all (inventory/resources input)]
    (if (and (selection/complete? (:kubernetes/nodes input)) (inventory/unique-identities? all)) all (conflict))))

(defn- ^{:stratum 1} admitted-runtime [resources membership]
  (let [outside-pods (filterv #(= "Pod" (get % "kind")) (inventory/outside resources membership))]
    (if (m/validate [:vector runtime/Pod] outside-pods) resources (invalid))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project [input]
  (anomaly/let-ok [input (admitted input)
                  selected (membership input)
                  all (resources input)
                  all (admitted-runtime all selected)]
    (projection/project input selected all)))
