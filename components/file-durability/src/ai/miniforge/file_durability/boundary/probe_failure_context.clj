;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.probe-failure-context
  "Keep unresolved native ownership actionable without discarding its primary failure."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.resource-cleanup.interface :as cleanup]
            [slingshot.slingshot :refer [get-thrown-object throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} operation-context [state]
  (let [returned @(:result state)
        original (some-> @(:primary state) get-thrown-object)]
    (cond
      (anomaly/anomaly? returned) returned
      (anomaly/anomaly? original) original
      :else (boundary/io-failure :native-probe (:root state)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} contextual [state]
  (let [primary (operation-context state)]
    (assoc-in @(:unreaped state) [:anomaly/data :durability/primary-failure] primary)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} propagate! [state failure]
  (let [critical (cleanup/fatal-cause failure)
        primary @(:primary state)]
    (cond
      critical (cleanup/propagate! critical)
      (and @(:unreaped state) (or primary (anomaly/anomaly? @(:result state))))
      (throw+ (contextual state) (or primary failure))
      :else (throw failure))))

(comment (get-thrown-object (java.io.IOException.)))
