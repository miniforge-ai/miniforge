;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.decision.spec
  "Malli schemas for canonical decision checkpoints and episodes.
   Layer 0: Enums and registry
   Layer 1: Checkpoint and episode schemas
   Layer 2: Validation helpers"
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

;; Enums and registry
(def ^{:stratum 0} source-kinds
  [:control-plane-agent :loop-escalation :workflow-node :external-agent :policy-gate])

(def ^{:stratum 0} authority-kinds
  [:human :system :model :rule :hybrid])

(def ^{:stratum 0} checkpoint-statuses
  [:pending :resolved :cancelled :expired])

(def ^{:stratum 0} episode-statuses
  [:pending :resolved :completed])

(def ^{:stratum 0} response-types
  [:approve :approve-with-constraints :reject :choose-option
   :request-more-evidence :reroute :mark-delegable :always-escalate :defer])

(def ^{:stratum 0} risk-tiers
  [:low :medium :high :critical])

(defn ^{:stratum 0} valid?
  [schema value]
  (m/validate schema value))

(defn ^{:stratum 0} explain
  [schema value]
  (some-> (m/explain schema value)
          me/humanize))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} registry
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

(defn ^{:stratum 1} validate-result
  "Return `value` when it validates against `schema`, else return an anomaly."
  [schema value]
  (if (valid? schema value)
    value
    (anomaly/anomaly :invalid-input
                     "Decision schema validation failed"
                     {:schema schema
                      :value value
                      :errors (explain schema value)})))

;------------------------------------------------------------------------------ Layer 2

;; Canonical model
(def ^{:stratum 2} Alternative
  "Schema for one proposed alternative."
  [:map {:registry registry}
   [:id any?]
   [:summary :id/string]])

(def ^{:stratum 2} DecisionSource
  "Schema for checkpoint source metadata."
  [:map {:registry registry}
   [:kind :source/kind]
   [:agent-id {:optional true} :id/uuid]
   [:agent {:optional true} :id/string]
   [:origin-run-id {:optional true} :id/string]
   [:loop-id {:optional true} :id/uuid]])

(def ^{:stratum 2} DecisionTask
  "Schema for task metadata attached to a checkpoint."
  [:map {:registry registry}
   [:kind keyword?]
   [:goal {:optional true} :id/string]
   [:task-id {:optional true} :id/uuid]])

(def ^{:stratum 2} DecisionUncertainty
  "Schema for uncertainty information."
  [:map {:registry registry}
   [:class keyword?]
   [:reason :id/string]
   [:agent-confidence {:optional true} [:double {:min 0.0 :max 1.0}]]])

(def ^{:stratum 2} DecisionRisk
  "Schema for risk data."
  [:map {:registry registry}
   [:tier :risk/tier]
   [:critical-paths {:optional true} [:vector :id/string]]
   [:requires-owner-review? {:optional true} boolean?]])

(def ^{:stratum 2} ControlPlaneContext
  [:map {:registry registry}
   [:context/text {:optional true} :id/string]
   [:context/deadline {:optional true} :common/timestamp]
   [:context/tags {:optional true} [:set keyword?]]])

(def ^{:stratum 2} LoopEscalationContext
  [:map {:registry registry}
   [:loop/iteration int?]
   [:loop/state keyword?]
   [:loop/termination [:map [:reason keyword?]]]
   [:task/type {:optional true} keyword?]
   [:error-count int?]])

(def ^{:stratum 2} DecisionResponse
  "Schema for the structured supervision response."
  [:map {:registry registry}
   [:type :response/type]
   [:value {:optional true} any?]
   [:constraints {:optional true} [:vector :id/string]]
   [:rationale {:optional true} :id/string]
   [:authority-role :authority/kind]])

(defn ^{:stratum 2} validate
  "Boundary wrapper around the canonical anomaly-returning
   [[validate-result]]. Returns `value` on success and throws only for
   legacy callers. Prefer `validate-result` in non-boundary code."
  [schema value]
  (let [result (validate-result schema value)]
    (if (anomaly/anomaly? result)
      (throw (ex-info (:anomaly/message result)
                      (:anomaly/data result)))
      result)))

;------------------------------------------------------------------------------ Layer 3

(def ^{:stratum 3} DecisionProposal
  "Schema for the proposed action under judgment."
  [:map {:registry registry}
   [:action-type keyword?]
   [:decision-class keyword?]
   [:summary :id/string]
   [:alternatives {:optional true} [:vector Alternative]]
   [:files {:optional true} [:vector :id/string]]
   [:diff-summary {:optional true} :id/string]])

(def ^{:stratum 3} DecisionContext
  [:or ControlPlaneContext LoopEscalationContext])

;------------------------------------------------------------------------------ Layer 4

(def ^{:stratum 4} DecisionCheckpoint
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
   [:context {:optional true} DecisionContext]
   [:response {:optional true} DecisionResponse]])

;------------------------------------------------------------------------------ Layer 5

(def ^{:stratum 5} DecisionEpisode
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
