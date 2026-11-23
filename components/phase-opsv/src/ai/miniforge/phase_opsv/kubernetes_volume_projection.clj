;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-projection
  (:require [ai.miniforge.phase-opsv.kubernetes-volume-references :as references]
            [ai.miniforge.phase-opsv.kubernetes-volume-rewrite :as rewrite]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} kind
  "Versioned, narrow service-account binding semantics; not a native manifest or verification verdict."
  :kubernetes/service-account-volume-bindings-v1)

(defn- ^{:stratum 0} container-field [plan spec field]
  (if (contains? spec field)
    (update spec field (partial mapv (partial rewrite/container plan)))
    spec))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} project [plan spec]
  (let [containers (reduce (partial container-field plan) spec references/container-fields)
        canonical (rewrite/collection plan containers "volumes")]
    {:kubernetes/projection kind
     :kubernetes/pod-spec canonical}))

(comment ::retain-raw-observations-separately-from-this-semantic-representation)
