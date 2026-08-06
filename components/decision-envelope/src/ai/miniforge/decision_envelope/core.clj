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
(ns ai.miniforge.decision-envelope.core
  "Construction and validation for DecisionEnvelopes. The factory here
   is the ONLY way to mint an envelope: no public path accepts a
   caller-supplied decision value — callers pass reasons and
   obligations, and the decision is DERIVED (worst-wins) from what they
   imply. That derivation is the §13.4 no-ears rule in code: text
   cannot write the label plane."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.decision-envelope.schema :as schema]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

;; Derivation table
(def ^{:stratum 0} ^:private deny-codes
  "Reason codes that force :deny regardless of anything else."
  #{:reason/rule-violation
    :reason/unknown-enforcement
    :reason/unknown-severity
    :reason/missing-artifact
    :reason/gate-check-failed
    ;; A ceiling that denies only sometimes is not a ceiling. Both grant
    ;; reasons are deny-class, so an effect over budget or lacking
    ;; authority cannot be downgraded to an obligation and proceed.
    :reason/grant-exceeded
    :reason/grant-scope-mismatch
    :reason/grant-absent})

(def ^{:stratum 0} ^:private obligation-only-types
  "Obligation types compatible with :allow-with-obligations. An
   approval requirement is NOT among them — approval pending means the
   action is denied until the workflow clears it."
  #{:obligation/warn-recorded :obligation/audit-recorded})

(defn ^{:stratum 0} valid?
  "True when `e` satisfies the closed DecisionEnvelope schema."
  [e]
  (m/validate schema/DecisionEnvelope e))

;------------------------------------------------------------------------------ Layer 1

;; Decision derivation
(defn ^{:stratum 1} derive-decision
  "Worst-wins decision from reasons + obligations. Deny-class reasons
   or an approval obligation force :deny; otherwise any obligations
   yield :allow-with-obligations; otherwise :allow."
  [reasons obligations]
  (cond
    (some (comp deny-codes :reason/code) reasons) :deny
    (some #(= :obligation/approval-required (:obligation/type %)) obligations) :deny
    (seq (remove (comp obligation-only-types :obligation/type) obligations)) :deny
    (seq obligations) :allow-with-obligations
    :else :allow))

;------------------------------------------------------------------------------ Layer 2

;; Factory
(defn ^{:stratum 2} envelope
  "Mint a DecisionEnvelope. `reasons`/`obligations` are vectors per the
   schema; `pins` is {:pins/pack-revision :pins/rule-ids
   :pins/event-watermark}. The decision is derived, never supplied.
   Returns the envelope, or an `:invalid-input` anomaly when the inputs
   do not validate."
  [reasons obligations pins]
  (let [e {:envelope/id (random-uuid)
           :envelope/decision (derive-decision reasons obligations)
           :envelope/reasons (vec reasons)
           :envelope/obligations (vec obligations)
           :envelope/pins pins
           :envelope/produced-by :runtime
           :envelope/at (Instant/now)}]
    (if (m/validate schema/DecisionEnvelope e)
      e
      (anomaly/sub-anomaly :invalid-input
                           :anomalies.decision-envelope/invalid
                           "DecisionEnvelope inputs failed validation"
                           {:explain (me/humanize (m/explain schema/DecisionEnvelope e))}))))
