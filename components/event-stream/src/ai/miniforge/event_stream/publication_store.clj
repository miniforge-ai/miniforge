;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-store
  "Publication ports. Volatile acknowledgment is explicitly not durable storage."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-journal :as journal]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.journal-storage :as storage]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ports [commit! close!]
  {:commit! commit!
   :close! close!})

(defn- ^{:stratum 0} memory-receipt [_ event] event)

(defn- ^{:stratum 0} no-close! [] nil)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} volatile-store []
  (let [store (journal/create (model/empty-state) memory-receipt)]
    (ports (partial journal/commit! store) no-close!)))

(defn ^{:stratum 1} durable-store [directory]
  (let [store (storage/open! directory)]
    (if (anomaly/anomaly? store) store
        (ports (partial storage/commit! store) (partial storage/close! store)))))

(comment
  ::publication-storage-ports)
