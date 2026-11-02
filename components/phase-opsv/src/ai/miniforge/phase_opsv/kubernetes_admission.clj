;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-admission
  "Cross-resource consistency for a structurally admitted captured target."
  (:require [ai.miniforge.phase-opsv.kubernetes-identity-consistency :as identity]
            [ai.miniforge.phase-opsv.kubernetes-pod-consistency :as pod]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private namespaced-config-kinds #{"ConfigMap" "Secret"})

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

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} config-scope? [target resource]
  (let [namespace (resource/namespace-of resource)
        namespaced? (contains? namespaced-config-kinds (get resource "kind"))]
    (and (or (nil? namespace) (= target namespace))
         (or (some? namespace) (not namespaced?)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} consistent? [snapshot]
  (let [cluster (:kubernetes/cluster snapshot)
        namespace (:kubernetes/namespace snapshot)
        label (:kubernetes/node-pool-label snapshot)
        nodes (get-in snapshot [:kubernetes/nodes "items"])
        pods (get-in snapshot [:kubernetes/pods "items"])
        configuration (:kubernetes/configuration snapshot)
        context (capture-scope snapshot)
        resources (concat [cluster namespace] nodes pods configuration)]
    (and (= "kube-system" (resource/name-of cluster))
         (every? (comp nil? resource/namespace-of) [cluster namespace])
         (unique-by? resource/name-of nodes)
         (unique-by? resource/name-of pods)
         (unique-by? identity/logical-identity configuration)
         (identity/consistent? resources)
         (every? (partial node-usable? label) nodes)
         (every? (partial pod/complete? context) pods)
         (every? (partial config-scope? (:namespace context)) configuration))))

(comment ::unambiguous-declared-capture)
