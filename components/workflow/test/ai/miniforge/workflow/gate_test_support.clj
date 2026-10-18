;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-test-support
  (:require [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} interceptor {:config {:gates [:review-approved]}})

(defn ^{:stratum 0} phase-result [decision]
  {:name :review :status :completed :started-at 0
   :result {:status :success :output {:review/decision decision}}})

(defn ^{:stratum 0} context
  ([] (context (events/create-event-stream {:sinks []})))
  ([stream] {:execution/id (random-uuid) :event-stream stream}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} enter-approved [ctx]
  (assoc ctx :phase (phase-result :approved)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} lifecycle-interceptor [leave]
  (assoc interceptor :enter enter-approved :leave leave))

(comment
  (phase-result :approved))
