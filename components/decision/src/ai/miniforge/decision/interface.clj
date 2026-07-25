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
(def ^{:stratum 0} DecisionCheckpoint
  "Malli schema for a canonical normalized decision checkpoint."
  spec/DecisionCheckpoint)

(def ^{:stratum 0} DecisionEpisode
  "Malli schema for a normalized decision episode (pending or resolved)."
  spec/DecisionEpisode)

(def ^{:stratum 0} source-kinds
  "Vector of known checkpoint producer categories."
  spec/source-kinds)

(def ^{:stratum 0} authority-kinds
  "Vector of authorities that may answer a checkpoint."
  spec/authority-kinds)

(def ^{:stratum 0} checkpoint-statuses
  "Vector of checkpoint lifecycle states."
  spec/checkpoint-statuses)

(def ^{:stratum 0} episode-statuses
  "Vector of episode lifecycle states."
  spec/episode-statuses)

(def ^{:stratum 0} response-types
  "Vector of structured supervision response actions."
  spec/response-types)

(def ^{:stratum 0} risk-tiers
  "Vector of risk tiers used for decision routing."
  spec/risk-tiers)

(def ^{:stratum 0} ControlPlaneContext
  "Malli schema for control-plane decision checkpoint context."
  spec/ControlPlaneContext)

(def ^{:stratum 0} LoopEscalationContext
  "Malli schema for loop-escalation checkpoint context."
  spec/LoopEscalationContext)

(def ^{:stratum 0} DecisionContext
  "Malli schema for the known decision-checkpoint context shapes."
  spec/DecisionContext)

;; Validation helpers
(def ^{:stratum 0} valid?
  "True when `value` validates against `schema`. (schema value) -> boolean."
  spec/valid?)

(def ^{:stratum 0} explain
  "Humanized explanation of why `value` fails `schema`. (schema value) -> map|nil."
  spec/explain)

(def ^{:stratum 0} validate-result
  "Return `value` if it validates against `schema`, else return an anomaly."
  spec/validate-result)

(def ^{:stratum 0} validate
  "Return `value` if it validates against `schema`, else throw. (schema value) -> value."
  spec/validate)

;------------------------------------------------------------------------------ Layer 1

;; Public constructors
(defn ^{:stratum 1} create-checkpoint
  "Create a canonical decision checkpoint."
  [opts]
  (validate DecisionCheckpoint
            (core/create-checkpoint opts)))

(defn ^{:stratum 1} resolve-checkpoint
  "Resolve a checkpoint with structured supervision."
  [checkpoint response]
  (validate DecisionCheckpoint
            (core/resolve-checkpoint checkpoint response)))

(defn ^{:stratum 1} create-episode
  "Create a decision episode shell from a checkpoint."
  [checkpoint]
  (validate DecisionEpisode
            (core/create-episode checkpoint)))

(defn ^{:stratum 1} update-episode
  "Update a decision episode from checkpoint state."
  ([episode checkpoint]
   (update-episode episode checkpoint nil))
  ([episode checkpoint opts]
   (validate DecisionEpisode
             (core/update-episode episode checkpoint opts))))

(defn ^{:stratum 1} create-control-plane-checkpoint
  "Normalize a control-plane decision request."
  [agent-id summary opts]
  (validate DecisionCheckpoint
            (core/create-control-plane-checkpoint agent-id summary opts)))

(defn ^{:stratum 1} create-loop-escalation-checkpoint
  "Normalize an inner-loop escalation."
  [loop-state opts]
  (validate DecisionCheckpoint
            (core/create-loop-escalation-checkpoint loop-state opts)))

(defn ^{:stratum 1} decision-response
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
