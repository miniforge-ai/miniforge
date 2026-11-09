;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-start-admission
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-absence :as ownership]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} readiness! [runtime input]
  (anomaly/let-ok [ready (admission/readiness! runtime input)
                  observed (ownership/observe! (:context ready) (dissoc input :effect/id))]
    (if (= :present (:load/resource-state observed))
      (assoc ready :ownership/observation observed)
      (assoc-in (admission/refused) [:anomaly/data :load/observation] observed))))

(comment
  (readiness! {} {}))
