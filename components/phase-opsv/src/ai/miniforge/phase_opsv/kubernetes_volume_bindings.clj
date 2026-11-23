;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-bindings
  (:require [ai.miniforge.phase-opsv.kubernetes-volume-references :as references]
            [ai.miniforge.phase-opsv.kubernetes-service-account-volume :as token]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} binding-map [selected]
  (if selected {(get selected "name") token/stable-binding} {}))

(defn- ^{:stratum 0} usable? [selected refs]
  (let [name (get selected "name")
        uses (filter #(= name (get % "name")) refs)]
    (or (nil? selected) (and (seq uses) (every? token/native-mount? uses)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} plan [spec]
  (let [volumes (get spec "volumes")
        names (map #(get % "name") volumes)
        declared (set names)
        refs (references/references spec)
        candidates (filter token/generated? volumes)
        selected (first candidates)]
    (when (and (= (count names) (count declared))
               (every? #(contains? declared (get % "name")) refs)
               (<= (count candidates) 1)
               (usable? selected refs))
      (binding-map selected))))

(comment ::only-one-recognized-generated-binding-all-other-identifiers-remain-literal)
