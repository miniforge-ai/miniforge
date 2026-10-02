;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.dispatch
  "Drain in commit order outside the publication lock, including reentrant sends."
  (:require [ai.miniforge.event-stream.boundary.critical :as critical]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.publication-state :as state]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} claim! [stream]
  (locking stream
    (when-not (get-in @stream [:publication :draining?])
      (swap! stream assoc-in [:publication :draining?] true)
      true)))

(defn- ^{:stratum 0} next-event! [stream]
  (locking stream
    (let [event (state/pending @stream)]
      ;; The empty observation and releasing the drainer are atomic with enqueue.
      ;; Otherwise a sender can strand its event behind a departing drainer.
      (when-not event (swap! stream assoc-in [:publication :draining?] false))
      event)))

(defn- ^{:stratum 0} handle-thrown! [stream throwable]
  (let [cause (critical/cause throwable)
        failure (model/failure :fault :publication/delivery-failed nil)]
    (swap! stream update :publication assoc :draining? false :delivery-failure failure)
    (if cause (critical/propagate! cause) failure)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} drain-owned! [stream deliver!]
  (try+
    (loop []
      (when-let [event (next-event! stream)]
        (deliver! event)
        (swap! stream state/delivered)
        (recur)))
    (catch Object _ (handle-thrown! stream (:throwable &throw-context)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} drain! [stream deliver!]
  (when (claim! stream) (drain-owned! stream deliver!)))

(comment
  ::ordered-delivery)
