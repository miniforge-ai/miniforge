;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-verification-preparation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-client :as client]
            [ai.miniforge.phase-opsv.boundary.kubernetes-environment-fingerprint :as fingerprint]
            [ai.miniforge.phase-opsv.boundary.kubernetes-preparation-request :as request]
            [ai.miniforge.phase-opsv.kubernetes-preparation :as preparation]
            [ai.miniforge.phase-opsv.verification-preparation :as receipt]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} prepare! [overrides declaration value]
  (anomaly/let-ok [context (request/admit declaration value)
                  captured (client/observe-verification! overrides (:kubernetes/declaration context))
                  projected (fingerprint/project captured (:comparison/bounds context))]
    (receipt/receipt value (preparation/observation projected))))

(comment ::no-writes-load-or-experiment-effects)
