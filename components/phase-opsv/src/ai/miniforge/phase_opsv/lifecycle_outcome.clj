;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.lifecycle-outcome
  "Canonical shared-phase result construction."
  (:require [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} empty-metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})

(defn- ^{:stratum 0} artifact-facts [output]
  (let [retained (get-in output [:anomaly/data :opsv/phase-output] output)]
    (when (contains? retained :opsv/phase-produced-artifact-refs)
      {:artifacts (:opsv/phase-produced-artifact-refs retained)})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} phase-result [output]
  (let [failed? (anomaly/any-anomaly? output)
        status (if failed? :error :success)
        base {:status status
              :output output
              :metrics empty-metrics}]
    (cond-> (merge base (artifact-facts output))
      failed? (assoc :error {:message (:anomaly/message output) :data (:anomaly/data output)}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finalized-result
  "Project the result returned by the evidence publication boundary."
  [output]
  (let [result (phase-result output)]
    (cond-> result
      (= :success (:status result))
      (assoc :workflow/evidence-bundle-id (:opsv/evidence-artifact-id output)))))

(comment
  (phase-result {}))
