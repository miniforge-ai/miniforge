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
(ns ai.miniforge.evidence-bundle.schema.opsv
  "Canonical N6 section 2.8 OPSV evidence schemas.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} KeywordMap [:map-of keyword? :any])

(defn ^{:stratum 0} unique-values?
  [values]
  (= (count values) (count (set values))))

(def ^{:stratum 0} EnvironmentFingerprint
  [:map {:closed true}
   [:cluster string?]
   [:node-pools [:vector string?]]
   [:image-digests [:map-of string? string?]]
   [:config-hash string?]])

(def ^{:stratum 0} RiskFactor
  [:map {:closed true}
   [:factor keyword?]
   [:input :any]
   [:contribution [:double {:min 0.0 :max 1.0}]]
   [:rationale string?]])

(def ^{:stratum 0} CriterionResult
  [:map {:closed true}
   [:criterion/id string?]
   [:criterion/passed? boolean?]
   [:criterion/observed :any]
   [:criterion/expected :any]
   [:criterion/reason-code keyword?]])

(def ^{:stratum 0} GovernedEffect
  [:map {:closed true}
   [:evidence/effect-id uuid?]
   [:evidence/grant-id uuid?]
   [:evidence/envelope-id uuid?]])

(def ^{:stratum 0} RollbackResult
  [:map {:closed true}
   [:status [:enum :not-required :not-triggered :succeeded :failed]]
   [:artifact-refs [:vector uuid?]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PolicyProposal
  [:map {:closed true}
   [:policy-hash string?]
   [:confidence keyword?]
   [:scaling KeywordMap]
   [:resources KeywordMap]
   [:artifact-id uuid?]])

(def ^{:stratum 1} RiskResult
  [:map {:closed true}
   [:score [:double {:min 0.0 :max 1.0}]]
   [:level [:enum :low :medium :high :critical]]
   [:factors [:vector RiskFactor]]])

(def ^{:stratum 1} VerificationResult
  [:map {:closed true}
   [:passed? boolean?]
   [:criteria-evaluation [:vector CriterionResult]]
   [:confidence keyword?]
   [:caveats [:vector string?]]])

(def ^{:stratum 1} ActuationResult
  [:map {:closed true}
   [:requested-actuation-mode
    [:enum :recommend-only :pr-only :apply-allowed]]
   [:effective-actuation-mode
    [:enum :none :recommend-only :pr-only :apply-allowed]]
   [:governed-effects
    [:and [:vector GovernedEffect] [:fn unique-values?]]]
   [:pr-refs [:vector string?]]
   [:apply-refs [:vector string?]]
   [:postcondition-artifact-refs [:vector uuid?]]
   [:rollback RollbackResult]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} OpsvEvidence
  [:map {:closed true}
   [:opsv/experiment-pack-hash string?]
   [:opsv/experiment-pack-id string?]
   [:opsv/experiment-pack-artifact-id uuid?]
   [:opsv/environment-fingerprint EnvironmentFingerprint]
   [:opsv/event-refs
    [:and [:vector {:min 1} uuid?] [:fn unique-values?]]]
   [:opsv/artifact-refs
    [:and [:vector {:min 1} uuid?] [:fn unique-values?]]]
   [:opsv/grant-refs
    [:and [:vector uuid?] [:fn unique-values?]]]
   [:opsv/risk-score RiskResult]
   [:opsv/convergence-iterations [:int {:min 0}]]
   [:opsv/policy-proposals [:vector PolicyProposal]]
   [:opsv/verification VerificationResult]
   [:opsv/actuation ActuationResult]
   [:opsv/metric-query-artifact-refs [:vector uuid?]]
   [:opsv/metric-snapshot-artifact-refs [:vector uuid?]]
   [:opsv/diff-artifact-refs [:vector uuid?]]])
