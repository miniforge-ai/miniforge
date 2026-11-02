;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-admission
  "Cross-resource consistency for a structurally admitted captured target."
  (:require [ai.miniforge.phase-opsv.kubernetes-identity-consistency :as identity]
            [ai.miniforge.phase-opsv.kubernetes-api :as api]
            [ai.miniforge.phase-opsv.kubernetes-pod-consistency :as pod]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unique-by? [selector resources]
  (= (count resources) (count (set (map selector resources)))))

(defn- ^{:stratum 0} node-usable? [label node]
  (and (nil? (resource/namespace-of node))
       (not (str/blank? (get-in node ["metadata" "labels" label])))))

(defn- ^{:stratum 0} capture-scope [snapshot]
  (let [namespace (resource/name-of (:kubernetes/namespace snapshot))
        nodes (get-in snapshot [:kubernetes/nodes "items"])
        node-names (set (map resource/name-of nodes))]
    {:namespace namespace
     :node-names node-names}))

(defn- ^{:stratum 0} config-scope? [target resource]
  (= target (resource/namespace-of resource)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} consistent? [snapshot]
  (let [cluster (:kubernetes/cluster snapshot)
        namespace (:kubernetes/namespace snapshot)
        label (:kubernetes/node-pool-label snapshot)
        nodes (get-in snapshot [:kubernetes/nodes "items"])
        pods (get-in snapshot [:kubernetes/pods "items"])
        configuration (:kubernetes/configuration snapshot)
        context (capture-scope snapshot)
        resources (concat [cluster namespace] nodes pods configuration)]
    (and (= api/cluster-identity-namespace (resource/name-of cluster))
         (every? (comp nil? resource/namespace-of) [cluster namespace])
         (unique-by? resource/name-of nodes)
         (unique-by? resource/name-of pods)
         (unique-by? identity/logical-identity configuration)
         (identity/consistent? resources)
         (every? (partial node-usable? label) nodes)
         (every? (partial pod/complete? context) pods)
         (every? (partial config-scope? (:namespace context)) configuration))))

(comment ::unambiguous-declared-capture)
