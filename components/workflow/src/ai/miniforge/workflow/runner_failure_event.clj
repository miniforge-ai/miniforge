;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-failure-event
  "Project authoritative execution or phase failures into terminal event facts."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} primary-error [execution-error phase]
  (or execution-error (get-in phase [:result :error]) (:error phase)))

(defn- ^{:stratum 0} as-anomaly [value]
  (when (anomaly/any-anomaly? value) value))

(defn- ^{:stratum 0} error-message [error cause]
  (or (when (string? error) error)
      (:message error) (:anomaly/message cause) (messages/t :status/failed)))

(defn- ^{:stratum 0} classified [error cause message]
  (failure/classify
   (assoc (select-keys cause [:anomaly/type :anomaly/subtype :anomaly/category])
          :exception/class (or (get-in cause [:anomaly/data :anomaly/ex-class])
                               (:anomaly/ex-class cause)
                               (get-in cause [:anomaly/data :exception/class])
                               (get-in error [:data :exception/class]))
          :error/message message)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} data [context]
  (let [errors (:execution/errors context)
        execution-error (last errors)
        phase-name (or (:phase execution-error) (:execution/current-phase context)
                       (get-in context [:phase :name]))
        phase (get-in context [:execution/phase-results phase-name] (:phase context))
        error (primary-error execution-error phase)
        cause (or (as-anomaly (:anomaly error)) (as-anomaly error)
                  (when-not execution-error
                    (as-anomaly (get-in phase [:result :output]))))
        message (error-message error cause)
        details (cond-> {:message message :errors errors}
                  cause (assoc :anomaly cause))
        options (cond-> {:failure/class (classified error cause message)}
                  (keyword? phase-name) (assoc :workflow/failure-phase phase-name))]
    {:error details :options options}))

(comment
  (data {}))
