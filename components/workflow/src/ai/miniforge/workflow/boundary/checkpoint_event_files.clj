;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.checkpoint-event-files
  "Observe retained JSON files in recorded-time groups, preserving unknown order."
  (:require [ai.miniforge.event-stream.interface :as events]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter ResolverStyle]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private event-file-pattern
  "Hidden JSON records may be announcements too."
  "*.json")

(def ^{:stratum 0} ^:private legacy-name-pattern #"\A(\d{8}T\d{6})(?:\d{3})?Z-[^/]+\.json\z")

(def ^{:stratum 0} ^:private snowflake-name-pattern #"\A[0-7][0-9a-f]{15}__\d{12}\.transit\.json\z")

(def ^{:stratum 0} ^:private strict-calendar
  (.withResolverStyle (DateTimeFormatter/ofPattern "uuuuMMdd'T'HHmmss") ResolverStyle/STRICT))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} file-time [file]
  (let [filename (str (fs/file-name file))
        calendar (second (re-matches legacy-name-pattern filename))
        recognized? (or calendar (re-matches snowflake-name-pattern filename))]
    (try+
      ;; The shared parser's SMART resolver normalizes February 30. Never guess.
      (when calendar (LocalDateTime/parse calendar strict-calendar))
      (when recognized? (events/event-file-ms filename))
      (catch Exception _ nil))))

(defn- ^{:stratum 1} event-files [events-directory id]
  (when-let [directory (events/workflow-events-dir events-directory id)]
    (fs/glob directory event-file-pattern {:hidden true})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} ordered-groups [events-directory id]
  (let [entries (map (juxt file-time identity) (event-files events-directory id))]
    (if (some (comp nil? first) entries)
      :unknown
      (->> entries (sort-by first >) (partition-by first)))))

(comment ::retained-event-file-ordering)
