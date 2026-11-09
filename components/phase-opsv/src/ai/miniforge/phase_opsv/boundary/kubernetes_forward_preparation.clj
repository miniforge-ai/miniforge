;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-preparation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission :as admission]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unrecorded! [runtime id]
  (anomaly/let-ok [existing (effect/read-record (:effects-directory runtime) id)]
    (when existing
      (anomaly/anomaly :conflict (msg/ts :experiment/native-start-existing) {:effect/transaction existing}))))

(defn- ^{:stratum 0} current-intent! [operation runtime input]
  (anomaly/let-ok [before ((:readiness operation) runtime input)
                  intent ((:prepare operation) before input)
                  current ((:readiness operation) runtime input)]
    (if (= (admission/snapshot (:context before)) (admission/snapshot (:context current)))
      (assoc current :proposal intent)
      (admission/refused))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare! [operation runtime input]
  (anomaly/let-ok [_unrecorded (unrecorded! runtime ((:effect-id operation) input))]
    (current-intent! operation runtime input)))

(comment
  (prepare! {} {} {}))
