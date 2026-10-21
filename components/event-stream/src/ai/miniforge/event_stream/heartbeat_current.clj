;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-current
  "Own a current heartbeat activation; never reconstruct progress from history."
  (:require [ai.miniforge.event-stream.boundary.heartbeat-scheduler :as scheduler]
            [ai.miniforge.event-stream.heartbeat-clock :as clock]
            [ai.miniforge.event-stream.heartbeat-observation :as observation]
            [ai.miniforge.event-stream.heartbeat-progress :as progress]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} initial-state [workflow-id phase sources]
  (let [identity {:workflow/id workflow-id
                  :workflow/phase phase
                  :heartbeat/activation-id (random-uuid)}
        model (progress/start identity (clock/wall-time) (clock/monotonic-ms) sources)]
    (atom {:progress model :closed? false :dropped 0})))

(defn ^{:stratum 0} stop! [handle opts]
  (observation/fence! (:heartbeat/state handle))
  (scheduler/stop! handle opts))

(defn ^{:stratum 0} observe! [handle source position]
  (observation/observe! (:heartbeat/state handle) source position))

(defn ^{:stratum 0} dropped-count [handle] (:dropped @(:heartbeat/state handle)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start! [stream workflow-id phase opts]
  (let [state (initial-state workflow-id phase (:progress-sources opts))
        task (partial observation/tick! stream state (:interval-ms opts))
        resources (scheduler/start! phase (:interval-ms opts) task)]
    (assoc resources :heartbeat/current? true :heartbeat/state state :heartbeat/phase-id phase)))

(comment ::owned-current-heartbeat)
