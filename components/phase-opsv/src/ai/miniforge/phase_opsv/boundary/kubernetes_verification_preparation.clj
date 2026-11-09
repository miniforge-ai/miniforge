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

(defn- ^{:stratum 0} project [context value captured]
  (anomaly/let-ok [projected (fingerprint/project captured (:comparison/bounds context))]
    (receipt/receipt value (preparation/observation projected))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [overrides declaration value]
  (anomaly/let-ok [context (request/admit declaration value)
                  captured (client/observe-verification! overrides (:kubernetes/declaration context))]
    (let [result (project context value captured)]
      {:preparation/capture captured
       :preparation/result result})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare! [overrides declaration value]
  (anomaly/let-ok [observed (observe! overrides declaration value)]
    (:preparation/result observed)))

(comment ::no-writes-load-or-experiment-effects)
