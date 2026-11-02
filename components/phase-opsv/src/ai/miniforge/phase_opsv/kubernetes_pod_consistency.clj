;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-pod-consistency
  "Correlate admitted Pod declarations with observed placement and image status."
  (:require [ai.miniforge.phase-opsv.kubernetes-images :as images]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} names [containers] (mapv #(get % "name") containers))

(defn- ^{:stratum 0} unique? [values] (= (count values) (count (set values))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} declared-names [pod [spec-field _status _kind]]
  (names (get-in pod ["spec" spec-field])))

(defn- ^{:stratum 1} group-complete? [pod [spec-field status-field _kind]]
  (let [declared (names (get-in pod ["spec" spec-field]))
        observed (names (get-in pod ["status" status-field]))]
    (and (unique? declared) (unique? observed) (= (set declared) (set observed)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} complete? [context pod]
  (let [declared (vec (mapcat (partial declared-names pod) images/container-groups))
        node-name (get-in pod ["spec" "nodeName"])]
    (and (= (:namespace context) (resource/namespace-of pod))
         (contains? (:node-names context) node-name)
         (unique? declared)
         (every? (partial group-complete? pod) images/container-groups))))

(comment ::correlated-pod-observations)
