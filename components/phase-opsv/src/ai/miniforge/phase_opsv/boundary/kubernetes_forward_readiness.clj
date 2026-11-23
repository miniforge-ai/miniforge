;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-readiness
  "Retain the actual commit-time N4 decision before admitting the native request."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as artifacts]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} content [operation ready record]
  (let [key (:material-observation-key operation)
        observed (get ready (:observation-key operation))]
    {:effect/transaction record
     :opsv/commit-decision-envelope (:decision ready)
     key observed}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} retain! [operation ready record]
  (let [ctx (:context ready)
        material (:material operation)
        report (content operation ready record)
        output {(nth material 2) report}]
    (anomaly/let-ok [value (artifacts/record ctx output material)]
      (let [safe (artifacts/recorded-output output material value)]
        (confirmation/publish-with-exception-handling ctx value (artifacts/begin-publication safe))))))

(comment ::acknowledged-commit-admission-before-native-dispatch)
