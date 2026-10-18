;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.supervisory-spec
  "Current-write N3 intervention facts and Spec snapshots; compose with the envelope."
  (:require [ai.miniforge.event-stream.supervisory-identity :as identity]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} EntityScope
  [:map
   [:supervisory/entity-key :uuid]
   [:workflow/id {:optional true} [:maybe :uuid]]])

(def ^{:stratum 0} InterventionFields
  [:multi {:dispatch :event/type}
   [:supervisory/intervention-requested
    [:and schema/InterventionRecord [:map [:intervention/state [:= :proposed]]]]]
   [:supervisory/intervention-state-changed
    [:map
     [:intervention/id :uuid]
     [:intervention/from-state schema/InterventionState]
     [:intervention/state schema/InterventionState]
     [:intervention/updated-at inst?]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} InterventionPayload
  [:and EntityScope InterventionFields
   [:map
    [:event/version [:= "2.0.0"]]
    [:scope/type [:= :supervisory-entity]]]
   [:fn identity/intervention-key?]])

(def ^{:stratum 1} SpecSnapshot
  [:and EntityScope
   [:map
    [:event/type [:= :supervisory/spec-upserted]]
    [:scope/type {:optional true} [:maybe [:= :supervisory-entity]]]
    [:supervisory/schema-version schema/SemanticVersion]
    [:supervisory/entity schema/SpecRecord]]
   [:fn identity/spec-key?]])

(comment
  InterventionPayload)
