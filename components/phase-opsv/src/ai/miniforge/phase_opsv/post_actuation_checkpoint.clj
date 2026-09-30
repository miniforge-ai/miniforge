;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.post-actuation-checkpoint
  "Retain successful actuation output before attempting evidence publication."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} snapshot-key :opsv/post-actuation-snapshot)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pending? [ctx]
  (contains? (:execution/input ctx) snapshot-key))

(defn ^{:stratum 1} capture [ctx phase-key output]
  (if (and (= :opsv/actuate phase-key)
           (contains? (:execution/opts ctx) :opsv/evidence-base)
           (not (anomaly/anomaly? output)))
    (assoc-in ctx [:execution/input snapshot-key] (snapshot/encode ctx :post-actuation output))
    ctx))

(defn ^{:stratum 1} restore [ctx]
  (snapshot/decode ctx :post-actuation (get-in ctx [:execution/input snapshot-key])))

(defn ^{:stratum 1} encoding-failure [ctx output]
  (let [encoded (get-in ctx [:execution/input snapshot-key])]
    (when (anomaly/anomaly? encoded)
      (assoc-in encoded [:anomaly/data :opsv/phase-output] output))))

(comment
  (pending? {}))
