;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-references
  "Collect ConfigMap and Secret dependencies; never resolve or read their values."
  (:require [ai.miniforge.phase-opsv.kubernetes-reference-sources :as sources]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reference-key [reference]
  ((juxt :reference/kind :reference/namespace :reference/name) reference))

(defn- ^{:stratum 0} merge-use [[_ references]]
  (assoc (first references) :reference/optional? (every? :reference/optional? references)))

(defn- ^{:stratum 0} container-references [namespace container]
  (concat (mapcat (partial sources/references namespace sources/environment-sources) (get container "env"))
          (mapcat (partial sources/references namespace sources/environment-from-sources) (get container "envFrom"))))

(defn- ^{:stratum 0} volume-references [namespace volume]
  (concat (sources/references namespace sources/volume-sources volume)
          (mapcat (partial sources/references namespace sources/projected-sources)
                  (get-in volume ["projected" "sources"]))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} pod-references [pod]
  (let [namespace (get-in pod ["metadata" "namespace"])
        spec (get pod "spec")
        containers (mapcat #(get spec %) ["containers" "initContainers" "ephemeralContainers"])
        pull-secrets (get spec "imagePullSecrets")]
    (concat (mapcat (partial container-references namespace) containers)
            (mapcat (partial volume-references namespace) (get spec "volumes"))
            (mapcat (partial sources/references namespace sources/image-pull-sources) pull-secrets))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} configuration-references [pods]
  (->> pods
       (mapcat pod-references)
       (group-by reference-key)
       (map merge-use)
       (sort-by reference-key)
       vec))

(comment ::pod-configuration-read-dependencies)
