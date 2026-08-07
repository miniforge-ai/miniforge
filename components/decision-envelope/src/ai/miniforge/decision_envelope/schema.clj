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
(ns ai.miniforge.decision-envelope.schema
  "The DecisionEnvelope shape (Ariadne step 1b, §13.3): the one
   runtime-constructed record of a policy decision. The schema is
   CLOSED — an envelope is a contract, not an open convention; unknown
   keys are rejected, unlike the open supervisory entity maps."
  (:require
   [ai.miniforge.policy-clause.interface :as clause]))

;------------------------------------------------------------------------------ Layer 0

;; Vocabulary
(def ^{:stratum 0} decisions
  "Envelope decisions, most to least restrictive."
  [:deny :allow-with-obligations :allow])

(def ^{:stratum 0} reason-codes
  "Registered reason codes. Free-form keywords are rejected — a reason
   a consumer cannot dispatch on is a reason nobody handles.
   `:reason/unknown-enforcement` and `:reason/unknown-severity` are the
   fail-closed codes for off-vocabulary policy metadata;
   `:reason/missing-artifact` preserves the nil-artifact-fails-the-gate
   discipline."
  [:reason/rule-violation
   :reason/unknown-enforcement
   :reason/unknown-severity
   :reason/missing-artifact
   :reason/gate-check-failed
   :reason/no-violations
   ;; Ariadne 2b — authority, not policy: the action may be legal and
   ;; still be beyond what this principal was granted.
   :reason/grant-exceeded
   :reason/grant-scope-mismatch
   :reason/grant-absent])

(def ^{:stratum 0} obligation-types
  "Registered obligation types: what an :allow-with-obligations or a
   pending-approval :deny commits the runtime to record or seek."
  [:obligation/approval-required
   :obligation/warn-recorded
   :obligation/audit-recorded])

(def ^{:stratum 0} Pins
  "What policy revision the decision was made against: the compiled
   pack revision, the rule ids evaluated, and the event-stream
   watermark at decision time. Replays and audits join on these."
  [:map {:closed true}
   [:pins/pack-revision [:maybe :string]]
   [:pins/rule-ids [:vector :keyword]]
   [:pins/event-watermark [:maybe :int]]])

;------------------------------------------------------------------------------ Layer 1

;; Sub-schemas
(def ^{:stratum 1} Reason
  [:map {:closed true}
   [:reason/code (into [:enum] reason-codes)]
   [:reason/rule-id {:optional true} :keyword]
   [:reason/severity {:optional true} clause/Severity]
   [:reason/detail :string]])

(def ^{:stratum 1} Obligation
  [:map {:closed true}
   [:obligation/type (into [:enum] obligation-types)]
   [:obligation/rule-id {:optional true} :keyword]
   [:obligation/detail {:optional true} :string]])

;------------------------------------------------------------------------------ Layer 2

;; The envelope
(def ^{:stratum 2} DecisionEnvelope
  "CLOSED map — see ns docstring. `:envelope/produced-by` admits only
   `:runtime`: the label plane is model-unwritable (§13.4). Agent
   output can REQUEST; only the runtime DECIDES — a forged envelope
   arriving as agent text is data shaped like an envelope, never an
   envelope."
  [:map {:closed true}
   [:envelope/id :uuid]
   [:envelope/decision (into [:enum] decisions)]
   [:envelope/reasons [:vector Reason]]
   [:envelope/obligations [:vector Obligation]]
   [:envelope/pins Pins]
   [:envelope/produced-by [:enum :runtime]]
   [:envelope/at inst?]])
