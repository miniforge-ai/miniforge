(ns ai.miniforge.decision.spec
  "Malli schemas for canonical decision checkpoints and episodes.
   Layer 0: Enums and registry
   Layer 1: Checkpoint and episode schemas"
  (:require
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Enums and registry

(def source-kinds
  "Known checkpoint producer categories."
  [:control-plane-agent :loop-escalation :workflow-node :external-agent :policy-gate])

(def authority-kinds
  "Who may answer the checkpoint."
  [:human :system :model :rule :hybrid])

(def checkpoint-statuses
  "Checkpoint lifecycle states."
  [:pending :resolved :cancelled :expired])

(def episode-statuses
  "Episode lifecycle states for the initial implementation."
  [:pending :resolved :completed])

(def response-types
  "Structured response actions supported by the initial model."
  [:approve :approve-with-constraints :reject :choose-option
   :request-more-evidence :reroute :mark-delegable :always-escalate :defer])

(def risk-tiers
  "Risk tiers for decision routing."
  [:low :medium :high :critical])

(def registry
  "Malli registry for decision types."
  {;; Common identifiers
   :id/uuid uuid?
   :id/string [:string {:min 1}]
   :common/timestamp inst?

   ;; Decision model enums
   :source/kind (into [:enum] source-kinds)
   :authority/kind (into [:enum] authority-kinds)
   :checkpoint/status (into [:enum] checkpoint-statuses)
   :episode/status (into [:enum] episode-statuses)
   :response/type (into [:enum] response-types)
   :risk/tier (into [:enum] risk-tiers)})

;------------------------------------------------------------------------------ Layer 1
;; Canonical model

(def Alternative
  "Schema for one proposed alternative."
  [:map {:registry registry}
   [:id any?]
   [:summary :id/string]])

(def DecisionSource
  "Schema for checkpoint source metadata."
  [:map {:registry registry}
   [:kind :source/kind]
   [:agent-id {:optional true} :id/uuid]
   [:agent {:optional true} :id/string]
   [:origin-run-id {:optional true} :id/string]
   [:loop-id {:optional true} :id/uuid]])

(def DecisionTask
  "Schema for task metadata attached to a checkpoint."
  [:map {:registry registry}
   [:kind keyword?]
   [:goal {:optional true} :id/string]
   [:task-id {:optional true} :id/uuid]])

(def DecisionProposal
  "Schema for the proposed action under judgment."
  [:map {:registry registry}
   [:action-type keyword?]
   [:decision-class keyword?]
   [:summary :id/string]
   [:alternatives {:optional true} [:vector Alternative]]
   [:files {:optional true} [:vector :id/string]]
   [:diff-summary {:optional true} :id/string]])

(def DecisionUncertainty
  "Schema for uncertainty information."
  [:map {:registry registry}
   [:class keyword?]
   [:reason :id/string]
   [:agent-confidence {:optional true} [:double {:min 0.0 :max 1.0}]]])

(def DecisionRisk
  "Schema for risk data."
  [:map {:registry registry}
   [:tier :risk/tier]
   [:critical-paths {:optional true} [:vector :id/string]]
   [:requires-owner-review? {:optional true} boolean?]])

(def DecisionResponse
  "Schema for the structured supervision response."
  [:map {:registry registry}
   [:type :response/type]
   [:value {:optional true} any?]
   [:constraints {:optional true} [:vector :id/string]]
   [:rationale {:optional true} :id/string]
   [:authority-role :authority/kind]])

(def DecisionCheckpoint
  "Canonical normalized checkpoint."
  [:map {:registry registry}
   [:checkpoint/id :id/uuid]
   [:checkpoint/status :checkpoint/status]
   [:checkpoint/created-at :common/timestamp]
   [:checkpoint/resolved-at {:optional true} :common/timestamp]
   [:checkpoint/requested-authority :authority/kind]
   [:source DecisionSource]
   [:task {:optional true} DecisionTask]
   [:proposal DecisionProposal]
   [:uncertainty {:optional true} DecisionUncertainty]
   [:risk {:optional true} DecisionRisk]
   [:context {:optional true} [:map-of keyword? any?]]
   [:response {:optional true} DecisionResponse]])

(def DecisionEpisode
  "Normalized decision episode. The first implementation captures
   pending and resolved episodes before downstream outcome mining."
  [:map {:registry registry}
   [:episode/id :id/uuid]
   [:episode/status :episode/status]
   [:episode/created-at :common/timestamp]
   [:episode/updated-at :common/timestamp]
   [:checkpoint DecisionCheckpoint]
   [:supervision {:optional true} DecisionResponse]
   [:execution-result {:optional true} [:map-of keyword? any?]]
   [:downstream-outcome {:optional true} [:map-of keyword? any?]]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  (m/validate DecisionCheckpoint
              {:checkpoint/id (random-uuid)
               :checkpoint/status :pending
               :checkpoint/created-at (java.util.Date.)
               :checkpoint/requested-authority :human
               :source {:kind :control-plane-agent}
               :proposal {:action-type :request-human-decision
                          :decision-class :choice
                          :summary "Choose one"}})

  :leave-this-here)
