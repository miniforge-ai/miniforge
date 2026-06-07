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

(ns ai.miniforge.decision.interface
  "Public API for canonical decision checkpoints and episodes."
  (:require
   [ai.miniforge.decision.spec :as spec]
   [ai.miniforge.decision.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def DecisionCheckpoint
  "Malli schema for a canonical normalized decision checkpoint."
  spec/DecisionCheckpoint)
(def DecisionEpisode
  "Malli schema for a normalized decision episode (pending or resolved)."
  spec/DecisionEpisode)

(def source-kinds
  "Set of known checkpoint producer categories."
  spec/source-kinds)
(def authority-kinds
  "Set of authorities that may answer a checkpoint."
  spec/authority-kinds)
(def checkpoint-statuses
  "Set of checkpoint lifecycle states."
  spec/checkpoint-statuses)
(def episode-statuses
  "Set of episode lifecycle states."
  spec/episode-statuses)
(def response-types
  "Set of structured supervision response actions."
  spec/response-types)
(def risk-tiers
  "Set of risk tiers used for decision routing."
  spec/risk-tiers)
(def ControlPlaneContext
  "Malli schema for control-plane decision checkpoint context."
  spec/ControlPlaneContext)
(def LoopEscalationContext
  "Malli schema for loop-escalation checkpoint context."
  spec/LoopEscalationContext)
(def DecisionContext
  "Malli schema for the known decision-checkpoint context shapes."
  spec/DecisionContext)

;------------------------------------------------------------------------------ Layer 1
;; Validation helpers

(def valid?
  "True when `value` validates against `schema`. (schema value) -> boolean."
  spec/valid?)
(def explain
  "Humanized explanation of why `value` fails `schema`. (schema value) -> string."
  spec/explain)
(def validate
  "Return `value` if it validates against `schema`, else throw. (schema value) -> value."
  spec/validate)

;------------------------------------------------------------------------------ Layer 2
;; Public constructors

(defn create-checkpoint
  "Create a canonical decision checkpoint."
  [opts]
  (validate DecisionCheckpoint
            (core/create-checkpoint opts)))

(defn resolve-checkpoint
  "Resolve a checkpoint with structured supervision."
  [checkpoint response]
  (validate DecisionCheckpoint
            (core/resolve-checkpoint checkpoint response)))

(defn create-episode
  "Create a decision episode shell from a checkpoint."
  [checkpoint]
  (validate DecisionEpisode
            (core/create-episode checkpoint)))

(defn update-episode
  "Update a decision episode from checkpoint state."
  ([episode checkpoint]
   (update-episode episode checkpoint nil))
  ([episode checkpoint opts]
   (validate DecisionEpisode
             (core/update-episode episode checkpoint opts))))

(defn create-control-plane-checkpoint
  "Normalize a control-plane decision request."
  [agent-id summary opts]
  (validate DecisionCheckpoint
            (core/create-control-plane-checkpoint agent-id summary opts)))

(defn create-loop-escalation-checkpoint
  "Normalize an inner-loop escalation."
  [loop-state opts]
  (validate DecisionCheckpoint
            (core/create-loop-escalation-checkpoint loop-state opts)))

(defn decision-response
  "Create a structured supervision response."
  [decision-type resolution & [rationale]]
  (validate spec/DecisionResponse
            (core/decision-response decision-type resolution rationale)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def checkpoint
    (create-control-plane-checkpoint
     (random-uuid)
     "Should I merge PR #42?"
     {:type :approval
      :priority :high
      :options ["yes" "no"]}))

  (def episode (create-episode checkpoint))

  (update-episode episode
                  (resolve-checkpoint checkpoint
                                      {:type :approve
                                       :value "yes"
                                       :authority-role :human}))

  :leave-this-here)
