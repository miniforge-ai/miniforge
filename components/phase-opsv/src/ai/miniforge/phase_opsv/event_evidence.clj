;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-evidence
  "Project published workflow events into durable assembly references."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} governed-effects [event]
  (or (:opsv/governed-effects event)
      (some-> (:opsv/governed-effect event) vector) []))

(defn- ^{:stratum 0} query-options [stream workflow-id]
  (if (events/current-event-stream? stream)
    {:scope [:workflow workflow-id]}
    {:workflow-id workflow-id}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} material [event]
  (let [effects (governed-effects event)
        sequences {(:event/id event) (:event/sequence-number event)}
        grants (mapv :evidence/grant-id effects)]
    {:opsv/event-refs [(:event/id event)]
     :opsv/event-sequences sequences
     :opsv/grant-refs grants
     :opsv/actuation {:governed-effects effects}}))

(defn ^{:stratum 1} capture! [store bundle-id stream workflow-id]
  ;; Capture the complete workflow range once per checkpoint, not on every audit retry.
  (anomaly/let-ok [published (events/get-events stream (query-options stream workflow-id))]
    (let [sequences (into {} (map (juxt :event/id :event/sequence-number)) published)]
      (evidence/accumulate-opsv-evidence! store bundle-id {:opsv/event-sequences sequences}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} accumulate! [store bundle-id event]
  (evidence/accumulate-opsv-evidence! store bundle-id (material event)))

(comment
  (governed-effects {}))
