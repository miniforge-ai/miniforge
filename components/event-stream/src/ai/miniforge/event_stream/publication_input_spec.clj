;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-input-spec
  "Sequence-free admission contracts for the reconciled chain and supervisory profiles."
  (:require [ai.miniforge.event-stream.chain-spec :as chain]
            [ai.miniforge.event-stream.supervisory-spec :as supervisory]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} EnvelopeDraft
  [:map
   [:event/id :uuid]
   [:event/type qualified-keyword?]
   [:event/timestamp inst?]
   [:event/version schema/SemanticVersion]
   [:message :string]
   [:event/sequence-number {:optional true} [:not :any]]
   [:timestamp {:optional true} [:not :any]]
   [:event/parent-id {:optional true} [:maybe :uuid]]
   [:workflow/id {:optional true} [:maybe :uuid]]
   [:workflow/phase {:optional true} [:maybe :keyword]]
   [:agent/id {:optional true} [:maybe :keyword]]
   [:agent/instance-id {:optional true} [:maybe :uuid]]
   [:pr/id {:optional true} [:maybe :uuid]]
   [:pack/id {:optional true} [:maybe :string]]
   [:repo/id {:optional true} [:maybe :string]]
   [:deployment/id {:optional true} [:maybe :string]]
   [:chain/run-id {:optional true} [:maybe :uuid]]
   [:scope/type {:optional true} [:maybe :keyword]]
   [:org/id {:optional true} [:maybe :uuid]]
   [:workspace/id {:optional true} [:maybe :uuid]]
   [:auth/context {:optional true} [:maybe :map]]])

(def ^{:stratum 0} SpecDraft
  "The current Spec snapshot producer uses the original envelope profile."
  [:and supervisory/SpecSnapshot [:map [:event/version [:= "1.0.0"]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} CurrentDraft
  "Only these reconciled profiles are supported; no generic envelope fallback."
  [:and EnvelopeDraft
   [:or chain/Payload supervisory/InterventionPayload SpecDraft]])

(comment
  CurrentDraft)
