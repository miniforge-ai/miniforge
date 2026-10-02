;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication
  "Commit, record once, then deliver. Inputs have been validated and redacted."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.dispatch :as dispatch]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.publication-state :as state]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} record! [stream event]
  (when-not (state/seen? @stream event) (swap! stream state/admit event))
  event)

(defn ^{:stratum 0} close! [stream]
  (locking stream
    (swap! stream assoc-in [:publication :closed?] true)
    ((get-in @stream [:publication :store :close!]))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} commit! [stream scope draft]
  (let [commit-port (get-in @stream [:publication :store :commit!])
        receipt (commit-port scope draft)]
    (if (anomaly/any-anomaly? receipt) receipt (record! stream receipt))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [stream scope draft deliver!]
  (let [receipt (locking stream
                  (if (get-in @stream [:publication :closed?])
                    (model/failure :unavailable :journal/closed draft)
                    (commit! stream scope draft)))]
    (when-not (anomaly/any-anomaly? receipt) (dispatch/drain! stream deliver!))
    receipt))

(comment
  ::acknowledged-publication)
