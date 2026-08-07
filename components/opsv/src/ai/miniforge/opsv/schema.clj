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
(ns ai.miniforge.opsv.schema
  "Closed domain contracts for N7 Operational Policy Synthesis.")

;------------------------------------------------------------------------------ Layer 0

;; Vocabularies and structural values
(def ^{:stratum 0} requested-actuation-modes
  [:recommend-only :pr-only :apply-allowed])

(def ^{:stratum 0} effective-actuation-modes
  [:none :recommend-only :pr-only :apply-allowed])

(def ^{:stratum 0} risk-levels
  [:low :medium :high :critical])

(def ^{:stratum 0} rollback-statuses
  [:not-required :not-triggered :succeeded :failed])

(def ^{:stratum 0} opsv-gate-ids
  [:instrumentation
   :environment
   :blast-radius
   :abort
   :actuation
   :evidence-completeness])

(def ^{:stratum 0} convergence-terminal-reasons
  [:success-criteria-satisfied
   :guardrail-abort
   :confidence-threshold-reached
   :iteration-limit-reached])

(def ^{:stratum 0} KeywordMap
  "Extensible keyword-keyed detail whose entries remain domain data."
  [:map-of :keyword any?])

(def ^{:stratum 0} Targets
  [:map {:closed true}
   [:services [:vector :string]]
   [:environments [:vector :string]]])

(def ^{:stratum 0} Workload
  [:map {:closed true}
   [:profile :keyword]
   [:mix [:vector any?]]
   [:warmup-seconds [:int {:min 0}]]
   [:cooldown-seconds [:int {:min 0}]]])

(def ^{:stratum 0} VerificationSummary
  [:map {:closed true}
   [:passed? :boolean]
   [:confidence :keyword]
   [:caveats [:vector :string]]])

(def ^{:stratum 0} RiskFactor
  [:map {:closed true}
   [:factor :keyword]
   [:input any?]
   [:contribution [:double {:min 0.0 :max 1.0}]]
   [:rationale :string]])

(defn ^{:stratum 0} required-risk-factors?
  [factors]
  (let [factor-names (map :factor factors)]
    (and (= (count factor-names) (count (distinct factor-names)))
         (every? (set factor-names)
                 [:environment-class :blast-radius :actuation-requested]))))

(defn ^{:stratum 0} ordered-risk-thresholds?
  [{:keys [medium high critical]}]
  (<= 0.0 medium high critical 1.0))

(def ^{:stratum 0} CriterionResult
  [:map {:closed true}
   [:criterion/id :string]
   [:criterion/passed? :boolean]
   [:criterion/observed any?]
   [:criterion/expected any?]
   [:criterion/reason-code :keyword]])

(def ^{:stratum 0} VerificationCriterion
  [:map {:closed true}
   [:criterion/id :string]
   [:criterion/expected any?]])

(def ^{:stratum 0} CriterionEvaluation
  [:map {:closed true}
   [:passed? :boolean]
   [:reason-code :keyword]])

(defn ^{:stratum 0} unique-criterion-ids?
  [criteria]
  (let [criterion-ids (map :criterion/id criteria)]
    (= (count criterion-ids) (count (distinct criterion-ids)))))

(def ^{:stratum 0} GovernedEffect
  [:map {:closed true}
   [:evidence/effect-id :uuid]
   [:evidence/grant-id :uuid]
   [:evidence/envelope-id :uuid]])

(def ^{:stratum 0} ConvergenceEvaluation
  [:map {:closed true}
   [:success-criteria-satisfied? :boolean]
   [:headroom-satisfied? :boolean]
   [:guardrail-abort? :boolean]
   [:confidence [:double {:min 0.0 :max 1.0}]]
   [:measurement-window-seconds [:int {:min 0}]]
   [:repetitions [:int {:min 0}]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} GateResult
  [:map {:closed true}
   [:gate/id (into [:enum] opsv-gate-ids)]
   [:gate/passed? :boolean]])

(defn ^{:stratum 1} complete-gate-results?
  [gate-results]
  (let [gate-ids (map :gate/id gate-results)]
    (and (= (count gate-ids) (count (distinct gate-ids)))
         (every? (set gate-ids) opsv-gate-ids))))

(def ^{:stratum 1} RiskLevelThresholds
  [:and
   [:map {:closed true}
    [:medium [:double {:min 0.0 :max 1.0}]]
    [:high [:double {:min 0.0 :max 1.0}]]
    [:critical [:double {:min 0.0 :max 1.0}]]]
   [:fn {:error/message "risk thresholds must be ordered"}
    ordered-risk-thresholds?]])

(def ^{:stratum 1} ConvergenceConfig
  [:map {:closed true}
   [:parameters
    [:and KeywordMap
     [:fn {:error/message "convergence parameters must be declared"} seq]]]
   [:iteration-limit [:int {:min 1}]]
   [:confidence-threshold [:double {:min 0.0 :max 1.0}]]
   [:minimum-measurement-window-seconds [:int {:min 0}]]
   [:required-repetitions [:int {:min 1}]]])

(def ^{:stratum 1} RollbackResult
  [:map {:closed true}
   [:status (into [:enum] rollback-statuses)]
   [:artifact-refs [:vector :uuid]]])

(def ^{:stratum 1} OperationalPolicy
  "N1 section 2.11 and N7 section 4.2 Operational Policy Proposal."
  [:map {:closed true}
   [:operational-policy/id :string]
   [:operational-policy/version :string]
   [:operational-policy/target-services [:vector :string]]
   [:operational-policy/target-envs [:vector :string]]
   [:operational-policy/scaling KeywordMap]
   [:operational-policy/resources KeywordMap]
   [:operational-policy/guardrails KeywordMap]
   [:operational-policy/verification-summary VerificationSummary]
   [:operational-policy/rollback-plan KeywordMap]
   [:operational-policy/evidence-refs [:vector :uuid]]])

(def ^{:stratum 1} RiskResult
  "Normalized, explainable N7 risk result using the N6 evidence shape."
  [:map {:closed true}
   [:score [:double {:min 0.0 :max 1.0}]]
   [:level (into [:enum] risk-levels)]
   [:factors [:vector RiskFactor]]])

(def ^{:stratum 1} VerificationResult
  "Per-criterion N7 verification result using the N6 evidence shape."
  [:map {:closed true}
   [:passed? :boolean]
   [:criteria-evaluation [:vector CriterionResult]]
   [:confidence :keyword]
   [:caveats [:vector :string]]])

(def ^{:stratum 1} VerificationRequest
  [:map {:closed true}
   [:criteria
    [:and
     [:vector {:min 1} VerificationCriterion]
     [:fn {:error/message "criterion identifiers must be unique"}
      unique-criterion-ids?]]]
   [:observations [:map-of :string any?]]
   [:evaluate-fn ifn?]
   [:confidence :keyword]
   [:caveats [:vector :string]]])

(def ^{:stratum 1} ConvergenceResult
  [:map {:closed true}
   [:terminal-reason (into [:enum] convergence-terminal-reasons)]
   [:iterations [:int {:min 1}]]
   [:state any?]
   [:evaluation ConvergenceEvaluation]])

;------------------------------------------------------------------------------ Layer 2

;; Canonical N1/N6/N7 records
(def ^{:stratum 2} ExperimentPack
  "N1 section 2.12 and N7 section 4.1 Experiment Pack."
  [:map {:closed true}
   [:experiment-pack/id :string]
   [:experiment-pack/version :string]
   [:experiment-pack/targets Targets]
   [:experiment-pack/workload Workload]
   [:experiment-pack/success-criteria KeywordMap]
   [:experiment-pack/guardrails KeywordMap]
   [:experiment-pack/convergence ConvergenceConfig]
   [:experiment-pack/actuation-intent
    (into [:enum] requested-actuation-modes)]
   [:experiment-pack/required-instrumentation [:vector :keyword]]])

(def ^{:stratum 2} EffectiveActuationInput
  [:map {:closed true}
   [:requested-actuation-mode (into [:enum] requested-actuation-modes)]
   [:verification-passed? :boolean]
   [:gate-results
    [:and
     [:vector {:min 6} GateResult]
     [:fn {:error/message "all OPSV gates must occur exactly once"}
      complete-gate-results?]]]
   [:safe-mode? :boolean]
   [:pr-capability-valid? :boolean]
   [:apply-capability-valid? :boolean]
   [:rollback-verified? :boolean]
   [:postconditions-configured? :boolean]])

(def ^{:stratum 2} RiskAssessment
  "Transparent N7 factor contributions and policy-owned level thresholds."
  [:map {:closed true}
   [:factors
    [:and
     [:vector {:min 3} RiskFactor]
     [:fn {:error/message "required risk factors must occur exactly once"}
      required-risk-factors?]]]
   [:level-thresholds RiskLevelThresholds]])

(def ^{:stratum 2} ConvergenceRequest
  [:map {:closed true}
   [:config ConvergenceConfig]
   [:initial-state any?]
   [:step-fn ifn?]
   [:evaluate-fn ifn?]])

(def ^{:stratum 2} ActuationRecord
  "Requested/effective actuation and correlated governed N10 effects."
  [:map {:closed true}
   [:requested-actuation-mode (into [:enum] requested-actuation-modes)]
   [:effective-actuation-mode (into [:enum] effective-actuation-modes)]
   [:governed-effects [:vector GovernedEffect]]
   [:pr-refs [:vector :string]]
   [:apply-refs [:vector :string]]
   [:postcondition-artifact-refs [:vector :uuid]]
   [:rollback RollbackResult]])
