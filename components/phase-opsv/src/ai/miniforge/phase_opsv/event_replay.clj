;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-replay
  "Reuse confirmed phase audit occurrences during checkpoint/publication retries."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.messages :as msg])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private retryable-types
  #{:gate/decision :opsv.actuation/emitted :opsv.actuation/disposition
    :opsv.experiment/planned :opsv.experiment/started :opsv/load-step
    :opsv.guardrail/abort :opsv.convergence/iteration :opsv.policy/proposed
    :opsv.verification/result})

(defn- ^{:stratum 0} sequence-acknowledgment [event sequence-number]
  (if (nat-int? sequence-number)
    (assoc event :event/sequence-number sequence-number)
    (anomaly/anomaly :unavailable (msg/ts :event/sequence-unavailable)
                     {:event/id (:event/id event)})))

(defn- ^{:stratum 0} stable-event-id [event]
  (let [digest (artifact/content-digest
                (dissoc event :event/id :event/timestamp :event/sequence-number))]
    (if (anomaly/anomaly? digest) digest
      (UUID/nameUUIDFromBytes (.getBytes ^String digest StandardCharsets/UTF_8)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} acknowledged-event [ctx event]
  (let [store (:opsv/evidence-assembly-store ctx)
        assembly (when store (evidence/get-opsv-assembly store (:opsv/evidence-bundle-id event)))]
    (when (and (= (:workflow/id event) (:evidence-bundle/workflow-id assembly))
               (contains? (:opsv/event-refs assembly) (:event/id event)))
      (sequence-acknowledgment event (get-in assembly [:opsv/event-sequences (:event/id event)])))))

(defn- ^{:stratum 1} retryable? [event]
  (and (contains? retryable-types (:event/type event))
       (uuid? (:opsv/evidence-bundle-id event))))

(defn- ^{:stratum 1} identified-event [event]
  (let [id (stable-event-id event)]
    (if (anomaly/anomaly? id) id (assoc event :event/id id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} identify [event]
  (if (retryable? event) (identified-event event) event))

(defn ^{:stratum 2} publish! [ctx stream event]
  (if-not (retryable? event)
    (events/publish! stream event)
    (locking stream
      (or (some #(when (= (:event/id event) (:event/id %)) %) (events/get-events stream))
          (acknowledged-event ctx event)
          (events/publish! stream event)))))

(comment
  (identify {:event/type :unrelated}))
