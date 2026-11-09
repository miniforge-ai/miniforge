;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-cleanup
  "API surface class 1: read-only cleanup construction from freshly admitted quiescent ownership."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-quiescence :as quiescence]
            [ai.miniforge.phase-opsv.boundary.retained-recovery-plan :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.kubernetes-load-cleanup :as model]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare! [ctx input]
  (anomaly/let-ok [_bounded (reporting/admit input)]
    (if-not (valid-arguments? [ctx input])
      (anomaly/anomaly :invalid-input (msg/ts :load/invalid-cleanup-request) {})
      (anomaly/let-ok [admitted (retained/read! ctx (:recovery/input input) (:recovery/artifact-ref input))
                      owned (quiescence/read! (:ownership/runtime input) (:recovery/plan admitted))]
        (model/prepare ctx input (:recovery/plan admitted) owned)))))

(comment ::reuse-the-closed-recovery-reference-runtime-and-new-effect-id-input-contract)
