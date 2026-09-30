;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-replay
  "Reuse confirmed actuation audit occurrences during evidence-only retries."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private retryable-types
  #{:gate/decision :opsv.actuation/emitted :opsv.actuation/disposition})

(defn- ^{:stratum 0} acknowledged? [ctx event]
  (when-let [store (:opsv/evidence-assembly-store ctx)]
    (let [assembly (evidence/get-opsv-assembly store (:opsv/evidence-bundle-id event))]
      (and (= (:workflow/id event) (:evidence-bundle/workflow-id assembly))
           (contains? (:opsv/event-refs assembly) (:event/id event))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} retryable? [event]
  (and (contains? retryable-types (:event/type event))
       (uuid? (:opsv/evidence-bundle-id event))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} identify [event]
  (if-not (retryable? event)
    event
    (let [digest (artifact/content-digest
                  (dissoc event :event/id :event/timestamp :event/sequence-number))]
      (if (anomaly/anomaly? digest) digest
        (assoc event :event/id (UUID/nameUUIDFromBytes (.getBytes ^String digest StandardCharsets/UTF_8)))))))

(defn ^{:stratum 2} publish! [ctx stream event]
  (if-not (retryable? event)
    (events/publish! stream event)
    (locking stream
      (or (when (acknowledged? ctx event) event)
          (some #(when (= (:event/id event) (:event/id %)) %) (events/get-events stream))
          (events/publish! stream event)))))

(comment
  (identify {:event/type :unrelated}))
