;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-failure-source
  "Select failure diagnostics without mixing unrelated execution or phase attempts."
  (:require [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} as-anomaly [value]
  (when (anomaly/any-anomaly? value) value))

(defn- ^{:stratum 0} matching-summary? [summary diagnostic]
  (and (map? summary) (map? diagnostic) (string? (:message summary))
       (= (:message summary) (:message diagnostic))))

(defn- ^{:stratum 0} retain-diagnostic [summary diagnostic]
  (let [data (merge (:data diagnostic) (:data summary))]
    (cond-> (merge diagnostic summary)
      (seq data) (assoc :data data))))

(defn- ^{:stratum 0} gate-cause [context phase-name phase]
  (let [entries (get-in context [:execution/response-chain :response-chain])
        entry (last (filter #(= phase-name (:operation %)) entries))]
    (when (and (seq (:phase/gate-errors phase))
               (= :anomalies.gate/validation-failed (:anomaly entry)))
      (:anomaly-map entry))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} phase-error [phase]
  (let [summary (:error phase)
        diagnostic (get-in phase [:result :error])]
    (cond
      (or (as-anomaly summary) (as-anomaly (:anomaly summary))) summary
      (matching-summary? summary diagnostic) (retain-diagnostic summary diagnostic)
      :else (or summary diagnostic))))

(defn- ^{:stratum 1} phase-cause [context phase-name phase error]
  (or (as-anomaly (get-in phase [:result :output]))
      (when-not error (gate-cause context phase-name phase))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} select-failure [context phase-name execution-error phase]
  (let [error (or execution-error (phase-error phase))
        cause (or (as-anomaly (:anomaly error)) (as-anomaly error)
                  (when-not execution-error
                    (phase-cause context phase-name phase error)))]
    {:error error :cause cause}))

(comment ::authoritative-failure-diagnostics)
