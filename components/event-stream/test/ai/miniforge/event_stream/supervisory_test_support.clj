;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.supervisory-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} intervention [event-type]
  (let [id (random-uuid)]
    {:event/type event-type
     :event/version "2.0.0"
     :scope/type :supervisory-entity
     :supervisory/entity-key id
     :intervention/id id
     :intervention/updated-at #inst "2026-10-04"}))

(defn ^{:stratum 0} snapshot []
  (let [id (random-uuid)]
    {:event/type :supervisory/spec-upserted
     :supervisory/entity-key id
     :supervisory/schema-version "2.0.0"
     :supervisory/entity {:spec/id id
                          :spec/title "Snapshot fixture"
                          :spec/status :active
                          :spec/origin :miniforge
                          :spec/created-at #inst "2026-10-04"
                          :spec/updated-at #inst "2026-10-04"}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} request []
  (merge (intervention :supervisory/intervention-requested)
         {:intervention/type :pause
          :intervention/target-type :workflow
          :intervention/target-id (random-uuid)
          :intervention/requested-by "fixture-operator"
          :intervention/request-source :tui
          :intervention/justification "Fixture rationale"
          :intervention/state :proposed
          :intervention/requested-at #inst "2026-10-04"}))

(defn ^{:stratum 1} change []
  (assoc (intervention :supervisory/intervention-state-changed)
         :intervention/from-state :proposed
         :intervention/state :approved))

(comment
  (request))
