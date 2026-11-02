;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-capture-plan
  "Bound the complete set of reads derived from an admitted Pod selection."
  (:require [ai.miniforge.phase-opsv.kubernetes-read-plan :as requests]
            [ai.miniforge.phase-opsv.kubernetes-references :as references]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private identity-resource-count
  "Every capture reads both the cluster identity and the target namespace."
  2)

(defn- ^{:stratum 0} node-name [pod] (get-in pod ["spec" "nodeName"]))

(defn- ^{:stratum 0} configuration-request [{:reference/keys [kind namespace name optional?]}]
  (requests/request kind namespace optional? name))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} node-requests [pods]
  (->> pods (map node-name) distinct sort
       (mapv (partial requests/request "Node" nil false))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create [pods]
  (let [nodes (node-requests pods)
        configuration (mapv configuration-request (references/configuration-references pods))
        total (+ identity-resource-count (count pods) (count nodes) (count configuration))]
    {:plan/nodes nodes
     :plan/configuration configuration
     :plan/resource-count total}))

(comment ::complete-bounded-capture-plan)
