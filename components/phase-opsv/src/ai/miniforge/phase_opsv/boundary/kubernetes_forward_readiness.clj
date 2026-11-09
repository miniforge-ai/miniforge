;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-readiness
  "Retain the actual commit-time N4 decision before admitting the native request."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as artifacts]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} material
  [:native-load-start-readiness :opsv-verification-report :opsv/native-load-start-readiness])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} retain! [ready record]
  (let [ctx (:context ready)
        output {:opsv/native-load-start-readiness
                {:effect/transaction record :opsv/commit-decision-envelope (:decision ready)
                 :load/observation (:ownership/observation ready)}}]
    (anomaly/let-ok [value (artifacts/record ctx output material)]
      (confirmation/publish-with-exception-handling ctx value (artifacts/begin-publication output)))))

(comment
  (count material))
