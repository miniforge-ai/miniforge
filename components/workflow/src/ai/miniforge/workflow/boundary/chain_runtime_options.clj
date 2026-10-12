;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.chain-runtime-options
  "Refuse explicit current-runtime misconfiguration before admission or effects."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.chain-runtime-spec :as spec]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (messages/t :chain/invalid-runtime-options) {}))

(defn- ^{:stratum 0} separate-storage? [opts]
  (let [directory (:chain/retention-directory opts)]
    (and (artifact/canonical-publication-directory? directory)
         (not= directory (events/current-journal-directory (:chain/event-stream opts))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} current-options [opts]
  (if (and (schema/valid? spec/CurrentOptions opts) (separate-storage? opts)) opts (invalid)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [value]
  (let [opts (when (map? value) (into {} value))]
    (cond
      (nil? value) {}
      (nil? opts) (invalid)
      (contains? opts :chain/event-stream) (current-options opts)
      :else opts)))

(comment
  (prepare {}))
