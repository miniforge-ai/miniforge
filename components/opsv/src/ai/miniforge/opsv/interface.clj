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
(ns ai.miniforge.opsv.interface
  "Public API for canonical OPSV contracts and Experiment Pack hashing."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.opsv.core :as core]
   [ai.miniforge.opsv.schema :as schema]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

;; Contract and vocabulary re-exports
(def ^{:stratum 0} requested-actuation-modes
  "Canonical N7 intent modes, ordered from least to most autonomous."
  schema/requested-actuation-modes)

(def ^{:stratum 0} effective-actuation-modes
  "Canonical N7 effective modes, including the N8-safe :none posture."
  schema/effective-actuation-modes)

(def ^{:stratum 0} risk-levels
  "Canonical N7 explainable-risk levels."
  schema/risk-levels)

(def ^{:stratum 0} rollback-statuses
  "Canonical N6 OPSV rollback dispositions."
  schema/rollback-statuses)

(def ^{:stratum 0} ExperimentPack
  "Closed Malli schema for an N1/N7 Experiment Pack."
  schema/ExperimentPack)

(def ^{:stratum 0} OperationalPolicy
  "Closed Malli schema for an N1/N7 Operational Policy Proposal."
  schema/OperationalPolicy)

(def ^{:stratum 0} VerificationSummary
  "Closed Malli schema for an Operational Policy verification summary."
  schema/VerificationSummary)

(def ^{:stratum 0} RiskFactor
  "Closed Malli schema for one explainable risk contribution."
  schema/RiskFactor)

(def ^{:stratum 0} RiskResult
  "Closed Malli schema for normalized explainable OPSV risk."
  schema/RiskResult)

(def ^{:stratum 0} CriterionResult
  "Closed Malli schema for one verification criterion result."
  schema/CriterionResult)

(def ^{:stratum 0} VerificationResult
  "Closed Malli schema for per-criterion OPSV verification."
  schema/VerificationResult)

(def ^{:stratum 0} GovernedEffect
  "Closed Malli schema correlating an N10 intent, OIR, and capability."
  schema/GovernedEffect)

(def ^{:stratum 0} RollbackResult
  "Closed Malli schema for the OPSV rollback disposition and evidence."
  schema/RollbackResult)

(def ^{:stratum 0} ActuationRecord
  "Closed Malli schema for requested/effective actuation and effects."
  schema/ActuationRecord)

(def ^{:stratum 0} ^:private invalid-domain-value-message
  "Stable programmer-facing message for OPSV contract violations."
  "Invalid OPSV domain value")

(defn- ^{:stratum 0} validation-result
  [message schema-name contract value]
  (if-let [explanation (m/explain contract value)]
    (anomaly/validation-anomaly
     message
     schema-name
     value
     (me/humanize explanation))
    value))

;------------------------------------------------------------------------------ Layer 1

;; Public validation boundary
(defn ^{:stratum 1} validate-experiment-pack
  "Return the pack unchanged when valid, otherwise an :invalid-input anomaly."
  [value]
  (validation-result invalid-domain-value-message
                     :opsv/experiment-pack schema/ExperimentPack value))

(defn ^{:stratum 1} validate-operational-policy
  "Return the policy unchanged when valid, otherwise an :invalid-input anomaly."
  [value]
  (validation-result invalid-domain-value-message
                     :opsv/operational-policy schema/OperationalPolicy value))

(defn ^{:stratum 1} validate-risk
  "Return the risk result unchanged when valid, otherwise an anomaly."
  [value]
  (validation-result invalid-domain-value-message
                     :opsv/risk-result schema/RiskResult value))

(defn ^{:stratum 1} validate-verification
  "Return the verification unchanged when valid, otherwise an anomaly."
  [value]
  (validation-result invalid-domain-value-message
                     :opsv/verification-result schema/VerificationResult value))

(defn ^{:stratum 1} validate-actuation
  "Return the actuation record unchanged when valid, otherwise an anomaly."
  [value]
  (validation-result invalid-domain-value-message
                     :opsv/actuation-record schema/ActuationRecord value))

;------------------------------------------------------------------------------ Layer 2

;; Canonical content identity
(defn ^{:stratum 2} experiment-pack-hash
  "Return a canonical SHA-256 hash for a valid pack, or a validation anomaly."
  [pack]
  (let [validated (validate-experiment-pack pack)]
    (if (anomaly/anomaly? validated)
      validated
      (core/experiment-pack-hash-impl validated))))
