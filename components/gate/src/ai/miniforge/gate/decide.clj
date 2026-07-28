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
   [ai.miniforge.decision-envelope.interface :as env]))

;------------------------------------------------------------------------------ Layer 0

;; Translation helpers
(defn- ^{:stratum 0} violation-detail
  [v]
  (str (or (:message v) (:current v) "policy rule violated")))

(defn- ^{:stratum 0} rule-id
  [v]
  (or (:rule-id v) (get-in v [:rule :rule/id]) :rule/unknown))

(defn ^{:stratum 0} missing-artifact-reason
  "The nil-artifact-fails-the-gate discipline as a reason (wired by 1d)."
  []
  {:reason/code :reason/missing-artifact
   :reason/detail "gates configured but no artifact was produced"})

(defn ^{:stratum 0} allowed?
  "True when the envelope's decision is not :deny."
  [envelope]
  (not= :deny (:envelope/decision envelope)))

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

;------------------------------------------------------------------------------ Layer 2

;; The kernel
(defn ^{:stratum 2} decide
  "Classified violations (from policy-pack `classify-violations`) +
   pins -> DecisionEnvelope. :hard-halt and unknown-class violations
   become deny reasons; :require-approval becomes an approval
   obligation (which the envelope derivation treats as :deny until an
   approval workflow clears it — the ratified 1c behavior change);
   :warn/:audit become recording obligations."
  [{:keys [blocking require-approval warnings audits unknown]} pins]
  (env/envelope
   (concat (map blocking->reason blocking)
           (map unknown->reason unknown))
   (concat (map (partial obligation :obligation/approval-required) require-approval)
           (map (partial obligation :obligation/warn-recorded) warnings)
           (map (partial obligation :obligation/audit-recorded) audits))
   pins))
