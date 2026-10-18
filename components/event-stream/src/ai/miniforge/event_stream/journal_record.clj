;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.journal-record
  "The immutable storage representation, not a second event envelope."
  (:require [ai.miniforge.artifact.interface :as artifact]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} format-version "1.0.0")

(defn ^{:stratum 0} event [record]
  (get-in record [:artifact/content :journal/event]))

(defn ^{:stratum 0} scope [record]
  (get-in record [:artifact/content :journal/scope]))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} wrap-event [scope event]
  (let [content {:journal/scope scope
                 :journal/event event}]
    (artifact/build-artifact {:id (:event/id event)
                              :type :telemetry
                              :version format-version
                              :content content})))

(comment
  ::immutable-event-record)
