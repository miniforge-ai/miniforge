;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-notifications
  "Project one persistence record onto existing log and event surfaces."
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.boundary.environment :as boundary]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private persist-tier-by-mode {:governed :remote :local :worktree})

(defn- ^{:stratum 0} log-persisted! [logger data]
  (let [reference (or (:bundle-path data) (:commit-sha data))
        message (messages/system :env/persisted {:reference reference})]
    (log/info logger :workflow :workflow/workspace-persisted {:message message :data data})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} event-data [context phase env-id data]
  (let [tier (get persist-tier-by-mode (:execution/mode context) :worktree)]
    {:phase phase
     :env-id env-id
     :branch (:branch data)
     :commit-sha (:commit-sha data)
     :bundle-path (:bundle-path data)
     :persist-tier tier}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} announce! [logger context phase env-id data]
  (let [record (event-data context phase env-id data)]
    (log-persisted! logger record)
    (when-let [stream (:event-stream context)]
      (boundary/publish-persisted! stream (:execution/id context) record))))

(comment ::workspace-persistence-notifications)
