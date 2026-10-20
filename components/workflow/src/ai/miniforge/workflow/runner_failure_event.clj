;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-failure-event
  "Project authoritative execution or phase failures into terminal event facts."
  (:require [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-failure-source :as source]))

;------------------------------------------------------------------------------ Layer 0

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
        execution-error (source/execution-error context)
        phase-name (or (:phase execution-error) (:execution/current-phase context)
                       (get-in context [:phase :name]))
        phase (get-in context [:execution/phase-results phase-name] (:phase context))
        {:keys [error cause]} (source/select-failure context phase-name execution-error phase)
        message (error-message error cause)
        details (cond-> {:message message :errors errors}
                  error (assoc :diagnostic error)
                  cause (assoc :anomaly cause))
        options (cond-> {:failure/class (classified error cause message)}
                  (keyword? phase-name) (assoc :workflow/failure-phase phase-name))]
    {:error details :options options}))

(comment
  (data {}))
