;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.current-recovery
  "Validate recovered external records without rewriting historical source data."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.publication-input :as input]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} current-record? [{:keys [scope event]}]
  (let [draft (dissoc event :event/sequence-number)
        prepared (input/prepare draft)]
    (and (not (anomaly/any-anomaly? prepared))
         (= [scope draft] prepared)
         (redaction/clean? draft))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} events [records]
  (if (every? current-record? records)
    (mapv :event records)
    (model/failure :invalid-input :publication/invalid-recovery nil)))

(comment
  (events []))
