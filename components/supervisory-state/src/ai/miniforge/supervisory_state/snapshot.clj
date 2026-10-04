;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.supervisory-state.snapshot
  "Stamp the canonical entity identity on every supervisory snapshot draft."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.supervisory-state.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:private true :stratum 0} identity-fields
  {:supervisory/spec-upserted :spec/id
   :supervisory/workflow-upserted :workflow-run/id
   :supervisory/agent-upserted :agent/id
   :supervisory/policy-evaluated :policy-eval/id
   :supervisory/attention-derived :attention/id
   :supervisory/task-node-upserted :task/id
   :supervisory/decision-upserted :decision/id
   :supervisory/intervention-upserted :intervention/id})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} entity-key [event-type entity]
  (if (= :supervisory/pr-upserted event-type)
    (when (and (:pr/repo entity) (:pr/number entity))
      [(:pr/repo entity) (:pr/number entity)])
    (get entity (get identity-fields event-type))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} attach-entity [envelope entity]
  (if (anomaly/any-anomaly? envelope)
    envelope
    (assoc envelope
           :supervisory/entity entity
           :supervisory/entity-key (entity-key (:event/type envelope) entity)
           :supervisory/schema-version schema/schema-version)))

(comment
  ::canonical-snapshot)
