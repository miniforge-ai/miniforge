;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-announcements
  "Read the latest retained checkpoint locator without inferring aliased ancestry."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.boundary.checkpoint-event-files :as files]
            [ai.miniforge.workflow.checkpoint-location :as location]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} read-location [id [_time file]]
  (location/from-event id (events/read-event-file (fs/file file))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} group-location [id files]
  ;; Same-time announcements may agree, but filename dialect cannot break a tie.
  (let [locators (vec (distinct (keep (partial read-location id) files)))]
    (case (count locators)
      0 nil
      1 (first locators)
      :unknown)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} latest [events-directory id]
  (let [groups (files/ordered-groups events-directory id)]
    (if (= :unknown groups) :unknown (some (partial group-location id) groups))))

(comment ::newest-checkpoint-announcement)
