;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-reference
  (:require [ai.miniforge.phase-opsv.kubernetes-resource :as resource]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private native-replica-set-groups
  "Native ReplicaSet families, including historical groups; version never removes the parent-resolution requirement."
  #{"apps" "extensions"})

(defn- ^{:stratum 0} api-group [reference]
  (some-> (get reference "apiVersion") (str/split #"/") first))

(defn ^{:stratum 0} controller [child]
  (let [references (filter #(true? (get % "controller")) (get-in child ["metadata" "ownerReferences"]))]
    (when (= 1 (count references)) (first references))))

(defn ^{:stratum 0} identifies? [parent reference]
  (and (some? parent) (some? reference)
       (= (get parent "apiVersion") (get reference "apiVersion"))
       (= (get parent "kind") (get reference "kind"))
       (= (resource/name-of parent) (get reference "name"))
       (= (resource/uid parent) (get reference "uid"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} native-replica-set? [reference]
  (and (= "ReplicaSet" (get reference "kind"))
       (contains? native-replica-set-groups (api-group reference))))

(defn ^{:stratum 1} owns? [parent child]
  (let [reference (controller child)]
    (and (identifies? parent reference)
         (= (resource/namespace-of parent) (resource/namespace-of child)))))

(comment ::controller-links-require-uid-name-kind-api-and-namespace)
