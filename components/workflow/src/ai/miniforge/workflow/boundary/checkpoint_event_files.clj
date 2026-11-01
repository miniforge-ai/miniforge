;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-event-files
  "Observe retained JSON files in recorded-time groups, preserving unknown order."
  (:require [ai.miniforge.event-stream.interface :as events]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private event-file-pattern
  "Hidden JSON records may be announcements too."
  "*.json")

(defn- ^{:stratum 0} file-entry [file]
  ;; Malformed legacy dates throw in the shared parser: uncertainty is data here.
  (let [time (try+ (events/event-file-ms (str (fs/file-name file)))
                    (catch Exception _ nil))]
    [time file]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} event-files [events-directory id]
  (when-let [directory (events/workflow-events-dir events-directory id)]
    (fs/glob directory event-file-pattern {:hidden true})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ordered-groups [events-directory id]
  (let [entries (map file-entry (event-files events-directory id))]
    (if (some (comp nil? first) entries)
      :unknown
      (->> entries (sort-by first >) (partition-by first)))))

(comment ::retained-event-file-ordering)
