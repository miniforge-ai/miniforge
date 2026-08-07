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
   [ai.miniforge.opsv.actuation :as actuation]
   [ai.miniforge.opsv.convergence :as convergence]
   [ai.miniforge.opsv.core :as core]
   [ai.miniforge.opsv.risk :as risk]
   [ai.miniforge.opsv.schema :as schema]
   [ai.miniforge.opsv.verification :as verification]
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

(def ^{:stratum 0} opsv-gate-ids
  "The complete N7 section 5.2 gate vocabulary."
  schema/opsv-gate-ids)

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

(def ^{:stratum 0} RiskAssessment
  "Closed Malli schema for transparent OPSV risk inputs and thresholds."
  schema/RiskAssessment)

(def ^{:stratum 0} convergence-terminal-reasons
  "Canonical bounded-loop terminal reasons from N7 section 3.4."
  schema/convergence-terminal-reasons)

(def ^{:stratum 0} ConvergenceConfig
  "Closed Malli schema for bounded and stabilized convergence."
  schema/ConvergenceConfig)

(def ^{:stratum 0} ConvergenceResult
  "Closed Malli schema for a terminal convergence result."
  schema/ConvergenceResult)

(def ^{:stratum 0} CriterionResult
  "Closed Malli schema for one verification criterion result."
  schema/CriterionResult)

(def ^{:stratum 0} VerificationResult
  "Closed Malli schema for per-criterion OPSV verification."
  schema/VerificationResult)

(def ^{:stratum 0} VerificationCriterion
  "Closed Malli schema for an OPSV success criterion."
  schema/VerificationCriterion)

(def ^{:stratum 0} GovernedEffect
  "Closed Malli schema correlating an N10 intent, OIR, and capability."
  schema/GovernedEffect)

(def ^{:stratum 0} RollbackResult
  "Closed Malli schema for the OPSV rollback disposition and evidence."
  schema/RollbackResult)

(def ^{:stratum 0} ActuationRecord
  "Closed Malli schema for requested/effective actuation and effects."
  schema/ActuationRecord)

(def ^{:stratum 0} EffectiveActuationInput
  "Closed Malli schema for an N4/N7/N8/N10 authority decision."
  schema/EffectiveActuationInput)

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

(defn ^{:stratum 1} assess-risk
  "Sum explicit factor contributions and classify the normalized score."
  [factors level-thresholds]
  (let [assessment {:factors factors :level-thresholds level-thresholds}
        validated (validation-result invalid-domain-value-message
                                     :opsv/risk-assessment
                                     schema/RiskAssessment
                                     assessment)]
    (if (anomaly/anomaly? validated)
      validated
      (risk/assess-risk-impl validated))))

(defn ^{:stratum 1} converge
  "Run a bounded deterministic convergence loop over trusted pure callbacks.

   Both callbacks receive state, the one-based iteration, and config. The step
   callback returns the next state; the evaluator returns a ConvergenceEvaluation."
  [config initial-state step-fn evaluate-fn]
  (let [request {:config config
                 :initial-state initial-state
                 :step-fn step-fn
                 :evaluate-fn evaluate-fn}
        validated (validation-result invalid-domain-value-message
                                     :opsv/convergence-request
                                     schema/ConvergenceRequest
                                     request)]
    (if (anomaly/anomaly? validated)
      validated
      (loop [state initial-state
             iteration 1]
        (let [next-state (step-fn state iteration config)
              evaluation (validation-result
                          invalid-domain-value-message
                          :opsv/convergence-evaluation
                          schema/ConvergenceEvaluation
                          (evaluate-fn next-state iteration config))]
          (if (anomaly/anomaly? evaluation)
            evaluation
            (if-let [reason (convergence/terminal-reason
                             config evaluation iteration)]
              {:terminal-reason reason
               :iterations iteration
               :state next-state
               :evaluation evaluation}
              (recur next-state (inc iteration)))))))))

(defn ^{:stratum 1} verify-policy
  "Evaluate every declared criterion and return the aggregate result."
  [criteria observations evaluate-fn confidence caveats]
  (let [request {:criteria criteria
                 :observations observations
                 :evaluate-fn evaluate-fn
                 :confidence confidence
                 :caveats caveats}
        validated (validation-result invalid-domain-value-message
                                     :opsv/verification-request
                                     schema/VerificationRequest
                                     request)]
    (if (anomaly/anomaly? validated)
      validated
      (let [evaluations
            (mapv (fn [criterion]
                    (validation-result
                     invalid-domain-value-message
                     :opsv/criterion-evaluation
                     schema/CriterionEvaluation
                     (evaluate-fn criterion
                                  (get observations (:criterion/id criterion)))))
                  criteria)]
        (if-let [invalid (some #(when (anomaly/anomaly? %) %) evaluations)]
          invalid
          (validation-result invalid-domain-value-message
                             :opsv/verification-result
                             schema/VerificationResult
                             (verification/verify-policy-impl
                              validated evaluations)))))))

(defn ^{:stratum 1} effective-actuation
  "Reduce requested autonomy according to verification and authority gates."
  [decision-input]
  (let [validated (validation-result invalid-domain-value-message
                                     :opsv/effective-actuation-input
                                     schema/EffectiveActuationInput
                                     decision-input)]
    (if (anomaly/anomaly? validated)
      validated
      (actuation/effective-actuation-impl validated))))

;------------------------------------------------------------------------------ Layer 2

;; Canonical content identity
(defn ^{:stratum 2} experiment-pack-hash
  "Return a canonical SHA-256 hash for a valid pack, or a validation anomaly."
  [pack]
  (let [validated (validate-experiment-pack pack)]
    (if (anomaly/anomaly? validated)
      validated
      (core/experiment-pack-hash-impl validated))))
