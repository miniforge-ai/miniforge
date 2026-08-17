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
  "Runtime authority preparation for one exact Kubernetes deployment."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.gate.interface :as gate]
   [ai.miniforge.phase-deployment.messages :as msg]
   [ai.miniforge.phase-deployment.policy :as policy]
   [clojure.string :as str])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} allow-decisions
  #{:allow :allow-with-obligations})

(def ^{:stratum 0} empty-classification
  {:blocking []
   :require-approval []
   :warnings []
   :audits []
   :unknown []})

(def ^{:stratum 0} deployment-policy-pins
  {:pins/pack-revision nil
   :pins/rule-ids [:deploy/resource-count-limit :deploy/gke-node-limit]
   :pins/event-watermark nil})

(def ^{:stratum 0} default-breach-dir-property
  ".miniforge/grant-breaches")

(defn- ^{:stratum 0} policy-warning
  [violation]
  {:rule-id (:violation/rule-id violation)
   :message (:violation/message violation)})

(defn- ^{:stratum 0} missing-preview-violation
  []
  {:rule-id :deploy/provision-preview-required
   :message (msg/t :policy/provision-preview-required)})

(defn- ^{:stratum 0} effect-scope
  [request]
  (dissoc request :workflow-run/status :effect/class :effect/preflight
          :default-context))

(defn- ^{:stratum 0} preflight-evidence
  [preflight]
  (reduce-kv (fn [evidence source-key proposal-key]
               (if-some [value (get preflight source-key)]
                 (assoc evidence proposal-key value)
                 evidence))
             {}
             {:app-label :app-label
              :rendered-yaml :deploy/rendered-yaml
              :server-dry-run :deploy/server-dry-run
              :rollback-info :deploy/rollback-info}))

(defn ^{:stratum 0} request
  "Build the closed runtime request for one preflight-approved deployment."
  [context effect-id target]
  {:workflow-run/id (or (:execution/id context) (:run-id context))
   :workflow-run/status :running
   :effect/id effect-id
   :effect/class :effect/deploy
   :effect/preflight {:preflight/type :preflight/deploy-policy-and-dry-run
                      :preflight/result :allow}
   :kustomize-dir (:kustomize-dir target)
   :context (:context target)
   :default-context (or (:default-context target) (:context target))
   :namespace (:namespace target)
   :deployment-name (:deployment-name target)})

(defn- ^{:stratum 0} exact-target
  [target]
  (assoc target :context (or (:context target)
                             (:context-name target)
                             (:default-context target))))

(defn ^{:stratum 0} preflight
  "Reject an absent manifest at the legacy governed-deploy seam."
  [rendered _policy-context]
  (if (str/blank? (str rendered))
    {:preflight/result :deny
     :preflight/violations [(msg/t :deploy/render-failed)]
     :preflight/rendered nil}
    {:preflight/result :allow
     :preflight/violations []
     :preflight/rendered rendered}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} breach-dir
  [context]
  (or (:grant-breach-dir context)
      (str (System/getProperty "user.home") "/" default-breach-dir-property)))

(defn ^{:stratum 1} policy-classification
  "Evaluate deployment policy against the Pulumi preview it was defined for."
  [context target]
  (if-some [preview (get-in context
                            [:execution/phase-results :provision :result :output])]
    (let [policy-context (:phase-config target)
          violations (keep identity
                           [(policy/check-resource-count preview policy-context)
                            (policy/check-gke-node-limit preview policy-context)])]
      (assoc empty-classification :warnings (mapv policy-warning violations)))
    (assoc empty-classification :blocking [(missing-preview-violation)])))

(defn ^{:stratum 1} permitted?
  "True only when the grant check and the DecisionEnvelope both allow."
  [{:authority/keys [authorization envelope]}]
  (and (grant/authorized? authorization)
       (contains? allow-decisions (:envelope/decision envelope))))

(defn- ^{:stratum 1} authority-record
  [request classification grant-record authorization envelope preflight]
  {:effect/id (:effect/id request)
   :effect/proposal
   (merge (effect-scope request)
          (preflight-evidence preflight))
   :authority/grant grant-record
   :authority/authorization authorization
   :authority/envelope envelope
   :authority/policy classification})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare
  "Evaluate policy, issue exact authority, and derive one deploy decision."
  [context effect-id target preflight ^Instant now]
  (let [target (exact-target target)
        preflight (if (map? preflight) preflight {})
        request (request context effect-id target)
        classification (policy-classification context target)
        grant-record (grant/issue-for-effect (breach-dir context) request now)]
    (if (anomaly/anomaly? grant-record)
      grant-record
      (let [authorization (grant/authorize
                           grant-record
                           {:effect/scope (effect-scope request)
                            :usage/count 1}
                           now)
            envelope (gate/decide classification deployment-policy-pins
                                  authorization)]
        (authority-record request classification grant-record authorization
                          envelope preflight)))))
