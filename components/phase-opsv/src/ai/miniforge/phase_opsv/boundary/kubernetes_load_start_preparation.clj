;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-start-preparation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start :as proposal]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-admission :as admission]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unrecorded! [runtime input]
  (anomaly/let-ok [existing (effect/read-record (:effects-directory runtime) (:effect/id input))]
    (when existing
      (anomaly/anomaly :conflict (msg/ts :experiment/native-start-existing) {:effect/transaction existing}))))

(defn- ^{:stratum 0} current-intent! [runtime input]
  (anomaly/let-ok [before (admission/readiness! runtime input)
                  intent (proposal/prepare! (:context before) input)
                  current (admission/readiness! runtime input)]
    (if (= (admission/snapshot (:context before)) (admission/snapshot (:context current)))
      (assoc current :proposal intent)
      (admission/refused))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare! [runtime input]
  (anomaly/let-ok [_unrecorded (unrecorded! runtime input)]
    (current-intent! runtime input)))

(comment
  (prepare! {} {}))
