;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-replica-metadata-fixtures
  (:require [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-preparation-fixtures :as preparation]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as native]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} metadata
  "Ordinary nonempty native metadata becomes protected reporting data on publication."
  {"labels" {"app.kubernetes.io/name" "catalog"}
   "annotations" {"owner" "platform"}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} annotate [routes path]
  (update-in routes [path "metadata"] merge metadata))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} input []
  (let [routes (reduce annotate (preparation/routes) [inventory/namespace-path inventory/deployment-path])]
    (native/input (preparation/input routes))))
