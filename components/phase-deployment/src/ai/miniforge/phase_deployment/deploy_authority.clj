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
(ns ai.miniforge.phase-deployment.deploy-authority
  "Authority for one exact Kubernetes deployment (Ariadne step 2d).

   Mirrors `pr-lifecycle.merge-authority`: issue a narrow runtime-owned
   grant, authorize the concrete scope, and decide. The runtime is the
   principal — a phase may request a deployment, it cannot mint the
   authority to perform one.

   The preflight basis here is deployment's own: the provider dry-run
   plus the existing `check-resource-count` and `check-gke-node-limit`.
   Those checks already existed and were never consulted before an
   apply; wiring them as the issuance basis is why this component reuses
   rather than reinvents them."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.phase-deployment.policy :as policy])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} allow-decisions
  "Envelope decisions that permit the apply. `:allow-with-obligations`
   is included: obligations are recorded, not blocking."
  #{:allow :allow-with-obligations})

(def ^{:stratum 0} empty-classification
  "No policy-pack violations to classify — deployment's preflight is its
   own dry-run plus the resource checks, folded in as the grant basis."
  {:blocking [] :require-approval [] :warnings [] :audits [] :unknown []})

(def ^{:stratum 0} unpinned-policy
  {:pins/pack-revision nil :pins/rule-ids [] :pins/event-watermark nil})

(defn ^{:stratum 0} breach-dir
  [context]
  (or (:grant-breach-dir context)
      (str (System/getProperty "user.home") "/.miniforge/grants")))

(defn ^{:stratum 0} preflight
  "Run the deployment checks that must precede a mutation, over the
   provider's dry-run render.

   Returns `{:preflight/result :allow|:deny :preflight/violations [...]
   :preflight/rendered s}`. A dry-run that did not render is `:deny` —
   we cannot show the apply is safe, and unverifiable is denied."
  [rendered check-context]
  (if (clojure.string/blank? (str rendered))
    {:preflight/result :deny
     :preflight/violations []
     :preflight/rendered nil}
    (let [violations (remove nil?
                             [(policy/check-resource-count rendered check-context)
                              (policy/check-gke-node-limit rendered check-context)])]
      {:preflight/result (if (seq violations) :deny :allow)
       :preflight/violations (vec violations)
       :preflight/rendered rendered})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} permitted?
  "True only when the grant check and the DecisionEnvelope both allow."
  [{:authority/keys [authorization envelope]}]
  (and (grant/authorized? authorization)
       (contains? allow-decisions (:envelope/decision envelope))))

(defn ^{:stratum 1} prepare
  "Issue, authorize, and decide authority for a preflight-approved
   deployment. Returns an authority record, or the issuer's anomaly when
   authority cannot be established at all."
  [context effect-id deploy-config preflight-result ^Instant now]
  (let [{:keys [kustomize-dir namespace context-name default-context
                deployment-name]} deploy-config
        request {:workflow-run/id (:run-id context)
                 :workflow-run/status :running
                 :effect/id effect-id
                 :effect/class :effect/deploy
                 :effect/preflight
                 {:preflight/type :preflight/deploy-policy-and-dry-run
                  :preflight/result preflight-result}
                 :kustomize-dir kustomize-dir
                 :context context-name
                 :default-context default-context
                 :namespace namespace
                 :deployment-name deployment-name}
        grant-record (grant/issue-for-effect (breach-dir context) request now)]
    (if (anomaly/anomaly? grant-record)
      grant-record
      (let [scope {:effect/id effect-id
                   :workflow-run/id (:run-id context)
                   :kustomize-dir kustomize-dir
                   :context (or context-name default-context)
                   :namespace namespace
                   :deployment-name deployment-name}
            authorization (grant/authorize grant-record
                                           {:effect/scope scope :usage/count 1}
                                           now)
            envelope (gate/decide empty-classification unpinned-policy
                                  authorization)]
        {:effect/id effect-id
         :effect/proposal scope
         :authority/grant grant-record
         :authority/authorization authorization
         :authority/envelope envelope}))))
