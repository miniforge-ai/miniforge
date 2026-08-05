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
(ns ai.miniforge.event-stream.schema.opsv
  "Canonical N3 section 3.14 OPSV event schemas.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} KeywordMap [:map-of :keyword any?])

(def ^{:stratum 0} Targets
  [:map {:closed true}
   [:services [:vector :string]]
   [:environments [:vector :string]]])

(def ^{:stratum 0} RiskFactor
  [:map {:closed true}
   [:factor :keyword]
   [:input any?]
   [:contribution [:double {:min 0.0 :max 1.0}]]
   [:rationale :string]])

(def ^{:stratum 0} CriterionResult
  [:map {:closed true}
   [:criterion/id :string]
   [:criterion/passed? :boolean]
   [:criterion/observed any?]
   [:criterion/expected any?]
   [:criterion/reason-code :keyword]])

(def ^{:stratum 0} GovernedEffect
  [:map {:closed true}
   [:evidence/intent-id :uuid]
   [:evidence/oir-id :uuid]
   [:evidence/capability-id :string]])

(def ^{:stratum 0} EnvironmentFingerprint
  [:map {:closed true}
   [:cluster :string]
   [:node-pools [:vector :string]]
   [:image-digests [:map-of :string :string]]
   [:config-hash :string]])

(def ^{:stratum 0} ^:private envelope-entries
  [[:event/id :uuid]
   [:event/timestamp inst?]
   [:event/version :string]
   [:event/sequence-number :int]
   [:workflow/id :uuid]
   [:opsv/evidence-bundle-id :uuid]
   [:org/id {:optional true} :uuid]
   [:workspace/id {:optional true} :uuid]
   [:repo/id {:optional true} :string]
   [:auth/context {:optional true} :map]
   [:message :string]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} RiskResult
  [:map {:closed true}
   [:score [:double {:min 0.0 :max 1.0}]]
   [:level [:enum :low :medium :high :critical]]
   [:factors [:vector RiskFactor]]])

(defn ^{:stratum 1} opsv-event-schema
  [event-type payload-entries]
  (into [:map [:event/type [:= event-type]]]
        (concat payload-entries envelope-entries)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} ExperimentPlanned
  (opsv-event-schema
   :opsv.experiment/planned
   [[:opsv/experiment-pack-hash :string]
    [:opsv/targets Targets]
    [:opsv/risk-score RiskResult]]))

(def ^{:stratum 2} ExperimentStarted
  (opsv-event-schema
   :opsv.experiment/started
   [[:opsv/experiment-pack-hash :string]
    [:opsv/environment-fingerprint EnvironmentFingerprint]]))

(def ^{:stratum 2} LoadStep
  (opsv-event-schema
   :opsv/load-step
   [[:opsv/step-id :string]
    [:opsv/intended-load KeywordMap]
    [:opsv/observed-load KeywordMap]]))

(def ^{:stratum 2} GuardrailAbort
  (opsv-event-schema
   :opsv.guardrail/abort
   [[:opsv/trigger :keyword]
    [:opsv/threshold KeywordMap]
    [:opsv/observed KeywordMap]
    [:opsv/rollback-action :keyword]]))

(def ^{:stratum 2} ConvergenceIteration
  (opsv-event-schema
   :opsv.convergence/iteration
   [[:opsv/iteration-id :string]
    [:opsv/params KeywordMap]
    [:opsv/observed-metrics-summary KeywordMap]]))

(def ^{:stratum 2} PolicyProposed
  (opsv-event-schema
   :opsv.policy/proposed
   [[:opsv/policy-hash :string]
    [:opsv/diff-artifact-refs [:vector :uuid]]
    [:opsv/confidence :keyword]]))

(def ^{:stratum 2} VerificationResult
  (opsv-event-schema
   :opsv.verification/result
   [[:opsv/passed? :boolean]
    [:opsv/criteria-evaluation [:vector CriterionResult]]
    [:opsv/confidence :keyword]
    [:opsv/caveats [:vector :string]]]))

(def ^{:stratum 2} ActuationEmitted
  (opsv-event-schema
   :opsv.actuation/emitted
   [[:opsv/requested-actuation-mode
     [:enum :recommend-only :pr-only :apply-allowed]]
    [:opsv/effective-actuation-mode
     [:enum :none :recommend-only :pr-only :apply-allowed]]
    [:opsv/governed-effects [:vector GovernedEffect]]
    [:opsv/pr-refs [:vector :string]]
    [:opsv/apply-refs [:vector :string]]]))

(def ^{:stratum 2} DriftDetected
  (opsv-event-schema
   :opsv.drift/detected
   [[:opsv/signal :keyword]
    [:opsv/deviation KeywordMap]
    [:opsv/suggested-rerun? :boolean]]))
