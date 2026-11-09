;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-readiness
  "Retain the actual commit-time N4 decision before admitting the native request."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as artifacts]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} retain! [operation ready record]
  (let [ctx (:context ready)
        material (:material operation)
        output {(nth material 2)
                {:effect/transaction record :opsv/commit-decision-envelope (:decision ready)
                 :load/observation (get ready (:observation-key operation))}}]
    (anomaly/let-ok [value (artifacts/record ctx output material)]
      (confirmation/publish-with-exception-handling ctx value (artifacts/begin-publication output)))))

(comment ::acknowledged-commit-admission-before-native-dispatch)
