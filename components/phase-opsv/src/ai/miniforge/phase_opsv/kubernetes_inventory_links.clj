;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-inventory-links
  (:require [ai.miniforge.phase-opsv.kubernetes-controller-reference :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unambiguous? [child]
  (<= (count (filter #(true? (get % "controller")) (get-in child ["metadata" "ownerReferences"]))) 1))

(defn- ^{:stratum 0} scope-matches? [parent child]
  (let [parent-namespace (resource/namespace-of parent)]
    (or (nil? parent-namespace) (= parent-namespace (resource/namespace-of child)))))

(defn- ^{:stratum 0} canonical-pod-chain? [by-uid deployment-uid pod]
  (let [reference (ownership/controller pod)
        owner-uid (get reference "uid")]
    (cond
      (= deployment-uid owner-uid) false
      (= ["apps/v1" "ReplicaSet"] [(get reference "apiVersion") (get reference "kind")])
      (contains? by-uid owner-uid)
      :else true)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} known-parent-consistent? [by-uid child]
  (let [reference (ownership/controller child)
        parent (get by-uid (get reference "uid"))]
    (or (nil? parent)
        (and (ownership/identifies? parent reference) (scope-matches? parent child)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} consistent? [inventory replica-sets pods]
  (let [roots [(:kubernetes/namespace inventory) (:kubernetes/deployment inventory)]
        by-uid (into {} (map (juxt resource/uid identity) (concat roots replica-sets pods)))
        deployment-uid (resource/uid (:kubernetes/deployment inventory))
        children (concat replica-sets pods)]
    (and (every? unambiguous? children)
         (every? (partial known-parent-consistent? by-uid) children)
         (every? (partial canonical-pod-chain? by-uid deployment-uid) pods))))

(comment ::unknown-replica-set-links-and-contradictory-known-identities-cannot-hide-members)
