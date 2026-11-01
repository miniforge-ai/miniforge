;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-announcements
  "Read the latest retained checkpoint locator without inferring aliased ancestry."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.checkpoint-location :as location]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private event-file-pattern
  "Retained workflow layout stores JSON events; hidden JSON files must not be skipped."
  "*.json")

(defn- ^{:stratum 0} read-step [id _previous file]
  (when-let [locator (location/from-event id (events/read-event-file (fs/file file)))]
    (reduced locator)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} event-files [events-directory id]
  (when-let [directory (events/workflow-events-dir events-directory id)]
    (->> (fs/glob directory event-file-pattern {:hidden true})
         (sort-by #(str (fs/file-name %)))
         reverse)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} latest [events-directory id]
  (reduce (partial read-step id) nil (event-files events-directory id)))

(comment ::newest-checkpoint-announcement)
