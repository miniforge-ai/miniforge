;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.current-stream
  "Validate creation options, own the journal and restore validated committed history."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.current-options :as validation]
            [ai.miniforge.event-stream.boundary.current-recovery :as recovery]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.core :as core]
            [ai.miniforge.event-stream.current-stream-spec :as spec]
            [ai.miniforge.event-stream.current-publication :as current]
            [ai.miniforge.event-stream.publication :as publication]
            [ai.miniforge.event-stream.publication-state :as state]
            [ai.miniforge.event-stream.publication-store :as store]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} close! [stream]
  (if (current/active? stream)
    (publication/close! stream)
    (model/failure :invalid-input :publication/not-current nil)))

(defn- ^{:stratum 0} restore-stream [opts journal events]
  (let [stream (core/create-event-stream (merge {:sinks []} opts))
        seen (into #{} (map :event/id) events)
        publication (assoc (state/initial journal) :seen seen)]
    (swap! stream assoc :events events :publication publication)
    stream))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} own-stream [opts journal]
  (let [transferred? (atom false)]
    (try+
      (anomaly/let-ok [events (recovery/events (:recovered-records journal))]
        (let [stream (restore-stream opts journal events)]
          (reset! transferred? true)
          stream))
      (finally (when-not @transferred? ((:close! journal)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create [opts]
  (let [valid? (validation/valid? spec/Options opts)
        journal (when valid? (store/durable-store (:journal-directory opts)))]
    (cond
      (not valid?) (model/failure :invalid-input :publication/invalid-options nil)
      (anomaly/any-anomaly? journal) journal
      :else (own-stream opts journal))))

(comment
  (create {}))
