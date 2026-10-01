;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-evidence
  "Project published workflow events into durable assembly references."
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} governed-effects [event]
  (or (:opsv/governed-effects event)
      (some-> (:opsv/governed-effect event) vector) []))

(defn- ^{:stratum 0} event-sequences [stream event]
  (let [published (events/get-events stream {:workflow-id (:workflow/id event)})
        current {(:event/id event) (:event/sequence-number event)}]
    (into current (map (juxt :event/id :event/sequence-number)) published)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} material [stream event]
  (let [effects (governed-effects event)
        sequences (event-sequences stream event)
        grants (mapv :evidence/grant-id effects)]
    {:opsv/event-refs [(:event/id event)]
     :opsv/event-sequences sequences
     :opsv/grant-refs grants
     :opsv/actuation {:governed-effects effects}}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} accumulate! [store bundle-id stream event]
  (evidence/accumulate-opsv-evidence! store bundle-id (material stream event)))

(comment
  (governed-effects {}))
