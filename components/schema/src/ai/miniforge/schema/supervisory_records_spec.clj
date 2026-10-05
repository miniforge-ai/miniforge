;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.supervisory-records-spec
  "Deployed projection shapes, not current-write intervention admission profiles.
   Optional origin and justification remain compatible until producer migration.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} spec-statuses
  [:draft :active :completed :archived])

(def ^{:stratum 0} intervention-states
  [:proposed :pending-human :approved :rejected :dispatched :applied :verified :failed])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} SpecStatus (into [:enum] spec-statuses))

(def ^{:stratum 1} InterventionState (into [:enum] intervention-states))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} SpecProjection
  "Long-lived work record, distinct from a frozen workflow-run/spec snapshot.
   Structured intent and string/keyword tags retain their existing wire types."
  [:map
   [:spec/id uuid?]
   [:spec/title [:string {:min 1}]]
   [:spec/status SpecStatus]
   [:spec/created-at inst?]
   [:spec/updated-at inst?]
   [:spec/description {:optional true} :string]
   [:spec/intent {:optional true} map?]
   [:spec/repo-url {:optional true} :string]
   [:spec/tags {:optional true} [:vector [:or :string :keyword]]]
   [:spec/origin {:optional true} keyword?]])

(def ^{:stratum 2} InterventionProjection
  "Open projection record. Validation alone does not grant execution authority."
  [:map
   [:intervention/id uuid?]
   [:intervention/type keyword?]
   [:intervention/target-type keyword?]
   [:intervention/target-id any?]
   [:intervention/requested-by [:string {:min 1}]]
   [:intervention/request-source keyword?]
   [:intervention/state InterventionState]
   [:intervention/requested-at inst?]
   [:intervention/updated-at inst?]
   [:intervention/justification {:optional true} [:maybe string?]]
   [:intervention/details {:optional true} [:maybe map?]]
   [:intervention/approval-required? {:optional true} boolean?]
   [:intervention/reason {:optional true} [:maybe string?]]
   [:intervention/outcome {:optional true} any?]])

(comment
  SpecProjection)
