;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-publication-fault-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-readiness :as readiness]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} refuse-publication! [captured value]
  (reset! captured value)
  (anomaly/anomaly :unavailable "injected readiness publication refusal" {}))

(defn- ^{:stratum 0} capture-retention! [captured retain! operation ready record]
  (let [raw (#'readiness/content operation ready record)
        result (retain! operation ready record)]
    (reset! captured {:raw raw
                     :result result})
    result))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fail-readiness! [kind captured publish! directory value]
  (if (= kind (get-in value [:artifact/metadata :opsv/material-kind]))
    (refuse-publication! captured value)
    (publish! directory value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute [execute! kind fixture]
  (let [value (atom nil)
        observed (atom nil)
        publish! artifact/publish!
        retain! readiness/retain!]
    (with-redefs [artifact/publish! (partial fail-readiness! kind value publish!)
                  readiness/retain! (partial capture-retention! observed retain!)]
      (let [[result calls] (execute! fixture)]
        {:result result
         :calls calls
         :artifact @value
         :retention @observed}))))

(comment ::failed-publication-observation-delegates-to-the-real-retainer)
