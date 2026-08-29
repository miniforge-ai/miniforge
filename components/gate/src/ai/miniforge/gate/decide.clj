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
(ns ai.miniforge.gate.decide
  "The fail-closed decide() kernel (Ariadne step 1c): compiled policy
   check classification in, DecisionEnvelope out. The decision itself
   is derived by the envelope factory (worst-wins) — this namespace
   only translates classified violations into registered reasons and
   obligations, with NO branch that lets an unclassifiable violation
   pass."
  (:require
   [ai.miniforge.decision-envelope.interface :as env]
   [ai.miniforge.gate.grant :as grant]
   [ai.miniforge.gate.messages :as msg]
   [ai.miniforge.gate.reason :as reason]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} violation-detail
  [v]
  (str (or (:message v)
           (:current v)
           (msg/system-t :decide/policy-rule-violated))))

(defn- ^{:stratum 0} rule-id
  [v]
  (or (:rule-id v) (get-in v [:rule :rule/id]) :rule/unknown))

(defn ^{:stratum 0} missing-artifact-reason
  "The nil-artifact-fails-the-gate discipline as a reason (wired by 1d)."
  []
  (reason/create :reason/missing-artifact
                 (msg/system-t :decide/missing-artifact)))

(defn ^{:stratum 0} allowed?
  "True when the envelope's decision is not :deny."
  [envelope]
  (not= :deny (:envelope/decision envelope)))

(defn- ^{:stratum 0} mechanical-failure-reasons
  "One Reason PER ERROR, not per gate — a gate that found five stale
   references used to surface only the first, and a repair loop cannot
   fix what the record never named."
  [result]
  (let [gate (name (get result :gate :unknown))
        errors (or (seq (:errors result)) [nil])]
    (mapv (fn [error]
            (reason/create :reason/gate-check-failed
                          (msg/system-t :decide/gate-failed
                                        {:gate gate
                                         :message (or (:message error)
                                                      (msg/system-t :decide/check-failed))})))
          errors)))

;------------------------------------------------------------------------------ Layer 1

;; Reason/obligation translation
(defn- ^{:stratum 1} unknown->reason
  [v]
  {:reason/code (if (= :unknown-severity (:classify/problem v))
                  :reason/unknown-severity
                  :reason/unknown-enforcement)
   :reason/rule-id (rule-id v)
   :reason/detail (violation-detail v)})

(defn- ^{:stratum 1} blocking->reason
  [v]
  {:reason/code :reason/rule-violation
   :reason/rule-id (rule-id v)
   :reason/detail (violation-detail v)})

(defn- ^{:stratum 1} obligation
  [type v]
  {:obligation/type type
   :obligation/rule-id (rule-id v)
   :obligation/detail (violation-detail v)})

(defn ^{:stratum 1} gates->envelope
  "Phase-level envelope from a `check-gates` result (Ariadne 1d): ONE
   envelope per gated transition. Policy-gate results already carry an
   envelope — their reasons and obligations merge in; a mechanical gate
   failure contributes a :reason/gate-check-failed; a nil artifact with
   gates configured contributes :reason/missing-artifact. Pins come
   from the first policy envelope present (nil pins otherwise)."
  [{:keys [results]} artifact-nil?]
  (let [gate-envs (keep :envelope results)
        mech-failures (remove :envelope (remove :passed? results))
        pins (or (:envelope/pins (first gate-envs))
                 {:pins/pack-revision nil
                  :pins/rule-ids []
                  :pins/event-watermark nil})]
    (env/envelope
     (concat (mapcat :envelope/reasons gate-envs)
             (mapcat mechanical-failure-reasons mech-failures)
             (when artifact-nil? [(missing-artifact-reason)]))
     (mapcat :envelope/obligations gate-envs)
     pins)))

;------------------------------------------------------------------------------ Layer 2

;; The kernel
(defn ^{:stratum 2} decide
  "Classified violations (from policy-pack `classify-violations`) +
   pins -> DecisionEnvelope. :hard-halt and unknown-class violations
   become deny reasons; :require-approval becomes an approval
   obligation (which the envelope derivation treats as :deny until an
   approval workflow clears it — the ratified 1c behavior change);
   :warn/:audit become recording obligations.

   The 3-arity form adds authority to policy (Ariadne 2b): pass the
   result of `execution-grant/authorize` and a grant breach becomes a
   deny reason alongside the rule violations, through the SAME envelope
   derivation — there is no second decision path for authority.

   The kernel stays pure: the caller resolves and checks the grant, and
   passes the answer in. Omitting the argument is how every non-effect
   caller (phase gating) keeps today's behavior exactly."
  ([classified pins] (decide classified pins nil))
  ([{:keys [blocking require-approval warnings audits unknown]} pins grant-result]
   (env/envelope
    (concat (map blocking->reason blocking)
            (map unknown->reason unknown)
            (grant/reasons grant-result))
    (concat (map (partial obligation :obligation/approval-required) require-approval)
            (map (partial obligation :obligation/warn-recorded) warnings)
            (map (partial obligation :obligation/audit-recorded) audits))
    pins)))
