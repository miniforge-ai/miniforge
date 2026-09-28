;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.observation
  "Read-only reconciliation: absence and ambiguity remain unresolved."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-provider-github.messages :as msg]
            [ai.miniforge.opsv-provider-github.transport :as transport]
            [ai.miniforge.opsv-provider-github.wire :as wire]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unresolved []
  (anomaly/anomaly :unavailable (msg/t :observe/unresolved) {}))

(defn- ^{:stratum 0} page-sequence? [response]
  (and (vector? response) (every? vector? response)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} exact-observation [payload response]
  (if-not (page-sequence? response)
    (unresolved)
    (let [matches (filter (partial wire/matching-pr? payload) (mapcat identity response))]
      (if (= 1 (count matches))
        {:effect/observed (wire/observation (first matches)) :effect/matched? true}
        (unresolved)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe! [runtime payload]
  (let [response (transport/request! runtime "GET" (wire/listing-path payload) nil true)]
    (exact-observation payload response)))

(comment
  (exact-observation {} []))
