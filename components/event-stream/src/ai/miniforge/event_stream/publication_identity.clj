;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-identity
  "Identity and provenance that redaction must never silently rewrite.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private envelope-and-payload-fields
  [:event/id :event/type :event/timestamp :event/version :event/parent-id
   :org/id :workspace/id :repo/id :agent/id :agent/instance-id :workflow/id
   :pr/id :pack/id :deployment/id :auth/context
   :chain/run-id :chain/definition-id :chain/definition-version
   :step/id :step/index :step/workflow-id
   :edge/id :edge/from-workflow-id :edge/to-workflow-id
   :supervisory/entity-key :intervention/id :intervention/target-id :intervention/requested-by])

(def ^{:stratum 0} ^:private snapshot-fields
  {:supervisory/spec-upserted [:spec/id :spec/origin :spec/created-at :spec/repo-url]})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} projection [event]
  [(select-keys event envelope-and-payload-fields)
   (select-keys (:supervisory/entity event) (get snapshot-fields (:event/type event)))])

(comment
  (projection {}))
