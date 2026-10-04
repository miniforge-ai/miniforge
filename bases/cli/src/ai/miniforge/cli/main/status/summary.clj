;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.status.summary
  "Reconstruct one workflow status from its retained events."
  (:require [ai.miniforge.cli.app-config :as app-config]
            [ai.miniforge.cli.main.util :as util]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow-resume.interface :as resume]
            [slingshot.slingshot :refer [throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reconstruct! [directory workflow-id]
  (let [context (resume/reconstruct-context directory workflow-id)]
    (if (anomaly/anomaly? context)
      (throw+ context (:anomaly/message context))
      context)))

(defn- ^{:stratum 0} stale-running? [last-updated]
  (when-let [last-updated-ms (util/timestamp->epoch-ms last-updated)]
    (let [configured (:running-stale-threshold-ms (app-config/status-config))
          fallback (:running-stale-threshold-ms app-config/default-status-config)
          threshold-ms (if (nat-int? configured) configured fallback)]
      (> (- (util/current-time-ms) last-updated-ms) threshold-ms))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} reconstructed-status [context last-updated]
  (cond
    (resume/completed? context) :completed
    (resume/failed? context) :failed
    (resume/paused? context) :paused
    (stale-running? last-updated) :stale
    :else :running))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-workflow [workflow-id]
  (let [directory (app-config/events-dir)
        history (events/read-workflow-events-by-id directory workflow-id)
        context (reconstruct! directory workflow-id)
        last-updated (:event/timestamp (last history))
        status (reconstructed-status context last-updated)
        spec-name (some-> context :workflow-spec :name)
        completed-task-count (count (:completed-dag-tasks context))]
    {:workflow-id workflow-id
     :status status
     :spec-name spec-name
     :event-count (:event-count context)
     :completed-phases (:completed-phases context)
     :completed-dag-task-count completed-task-count
     :last-updated last-updated}))

(comment
  ::read-workflow)
