;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-placement
  (:require [ai.miniforge.phase-opsv.kubernetes-pod-consistency :as pods]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [ai.miniforge.phase-opsv.kubernetes-resource-selection :as selection]
            [ai.miniforge.phase-opsv.kubernetes-workload-inventory :as inventory]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} context [input]
  {:namespace (get-in input [:kubernetes/inventory :runtime/target :namespace])
   :pool-label (:kubernetes/node-pool-label input)
   :nodes (into {} (map (juxt resource/name-of identity)) (get-in input [:kubernetes/nodes "items"]))
   :node-names (set (map resource/name-of (get-in input [:kubernetes/nodes "items"])))})

(defn ^{:stratum 0} pool [context pod]
  (get-in (:nodes context) [(get-in pod ["spec" "nodeName"]) "metadata" "labels" (:pool-label context)]))

(defn- ^{:stratum 0} unique-nodes? [input]
  (let [nodes (get-in input [:kubernetes/nodes "items"])
        resources (concat nodes (inventory/resources (:kubernetes/inventory input)))
        uids (map resource/uid resources)
        names (map resource/name-of nodes)]
    (and (= (count uids) (count (distinct uids)))
         (= (count names) (count (distinct names))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} consistent? [input context selected]
  (and (selection/complete? (:kubernetes/nodes input))
       (unique-nodes? input)
       (every? (partial pods/complete? context) selected)
       (every? #(not (str/blank? (pool context %))) selected)))
