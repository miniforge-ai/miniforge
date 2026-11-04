;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-controller-reference
  (:require [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} controller [child]
  (let [references (filter #(true? (get % "controller")) (get-in child ["metadata" "ownerReferences"]))]
    (when (= 1 (count references)) (first references))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} owns? [parent child]
  (let [reference (controller child)]
    (and (some? parent) (some? reference)
         (= (resource/namespace-of parent) (resource/namespace-of child))
         (= (get parent "apiVersion") (get reference "apiVersion"))
         (= (get parent "kind") (get reference "kind"))
         (= (resource/name-of parent) (get reference "name"))
         (= (resource/uid parent) (get reference "uid")))))

(comment ::controller-links-require-uid-name-kind-api-and-namespace)
