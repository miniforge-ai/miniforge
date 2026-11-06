;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-environment-lifecycle
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-replica-lifecycle :as lifecycle]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} entry [observed]
  [(:replica/id observed) (:kubernetes/pod-lifecycle observed)])

(defn- ^{:stratum 0} hashes [observations]
  (->> observations (map :content/hash) distinct sort vec))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} project [replicas]
  (let [pods (get-in replicas [:kubernetes/membership :kubernetes/pods "items"])
        observations (mapv lifecycle/project pods)]
    (or (first (filter anomaly/anomaly? observations))
        {:kubernetes/pod-lifecycle-hashes (hashes observations)
         :kubernetes/pod-lifecycles (into {} (map entry) observations)})))

(comment ::instance-multiplicity-is-admitted-separately-by-the-retained-contract)
