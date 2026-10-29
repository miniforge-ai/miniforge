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
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.gate.interface :as gate]
   ;; Registers the :deploy/no-secrets-in-manifests detector.
   [ai.miniforge.phase-deployment.manifest-credentials]
   [ai.miniforge.phase-deployment.messages :as msg]
   [ai.miniforge.phase-deployment.policy :as policy]
   [ai.miniforge.policy-pack.interface :as policy-pack])
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
   :pins/rule-ids [:deploy/resource-count-limit :deploy/gke-node-limit
                   :deploy/no-secrets-in-manifests]
   :pins/event-watermark nil})

(def ^{:stratum 0} default-breach-dir-relative-path
  "grant-breaches")

(defn- ^{:stratum 0} policy-blocker
  [violation]
  {:rule-id (:violation/rule-id violation)
   :message (:violation/message violation)})

(defn- ^{:stratum 0} missing-preview-violation
  []
  {:rule-id :deploy/provision-preview-required
   :message (msg/t :policy/provision-preview-required)})

(defn ^{:stratum 0} manifest-findings
  "The deployment pack's :deploy-scoped rules (for example
   :deploy/no-secrets-in-manifests) evaluated against the rendered manifests,
   as {:blocking :require-approval :warnings :audits} entries. No pack fails
   closed, as the provision gate does. The governed transaction checks this
   right after rendering, before the server dry run sends the manifests to
   the cluster API; authority evaluates it again for its decision record."
  [rendered-yaml]
  (let [packs (policy/deployment-policy-packs)]
    (cond
      (empty? packs)
      {:blocking [{:rule-id :deploy/deployment-pack-missing
                   :message (msg/t :policy/deployment-pack-missing)}]}

      (empty? rendered-yaml)
      {}

      :else
      (let [result (policy-pack/check-artifact
                    packs
                    {:artifact/content rendered-yaml
                     :artifact/path "rendered-manifests.yaml"}
                    {:phase :deploy :task-type :implement})]
        (into {}
              (map (fn [k]
                     [k (mapv #(hash-map :rule-id (:code %) :message (:message %))
                              (get result k))]))
              [:blocking :require-approval :warnings :audits])))))

(defn- ^{:stratum 0} canonical-input-anomaly
  [context target]
  (let [missing-fields (cond-> []
                         (nil? (:execution/id context)) (conj :execution/id)
                         (nil? (:context target)) (conj :context))]
    (when (seq missing-fields)
      (anomaly/anomaly
       :invalid-input
       (msg/t :deploy/authority-input-invalid)
       {:authority/missing-fields missing-fields}))))

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
  {:workflow-run/id (:execution/id context)
   :workflow-run/status :running
   :effect/id effect-id
   :effect/class :effect/deploy
   :effect/preflight {:preflight/type :preflight/deploy-policy-and-dry-run
                      :preflight/result :allow}
   :kustomize-dir (:kustomize-dir target)
   :context (:context target)
   :default-context (:context target)
   :namespace (:namespace target)
   :deployment-name (:deployment-name target)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} breach-dir
  [context]
  (or (:grant-breach-dir context)
      (str (config/miniforge-home) "/" default-breach-dir-relative-path)))

(defn ^{:stratum 1} policy-classification
  "Evaluate deployment policy before anything applies: resource limits against
   the Pulumi preview they were defined for, and the deployment pack's :deploy
   rules against the rendered manifests."
  [context target preflight]
  (let [manifests (manifest-findings (:rendered-yaml preflight))
        preview-classification
        (if-some [preview (get-in context
                                  [:execution/phase-results :provision :result :output])]
          (let [policy-context (:phase-config target)
                violations (keep identity
                                 [(policy/check-resource-count preview policy-context)
                                  (policy/check-gke-node-limit preview policy-context)])]
            (assoc empty-classification :blocking (mapv policy-blocker violations)))
          (assoc empty-classification :blocking [(missing-preview-violation)]))]
    (merge-with into preview-classification manifests)))

(defn ^{:stratum 1} permitted?
  "True only when the grant check and the DecisionEnvelope both allow."
  [{:authority/keys [grant authorization envelope]}]
  (and (some? grant)
       (grant/authorized? authorization)
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
  (if-some [invalid-input (canonical-input-anomaly context target)]
    invalid-input
    (let [preflight (if (map? preflight) preflight {})
          request (request context effect-id target)
          classification (policy-classification context target preflight)
          policy-envelope (gate/decide classification deployment-policy-pins)
          policy-allowed? (gate/decision-allowed? policy-envelope)
          grant-record (when policy-allowed?
                         (grant/issue-for-effect
                          (breach-dir context) request now))]
      (cond
        (not policy-allowed?)
        (authority-record request classification nil nil policy-envelope
                          preflight)

        (anomaly/anomaly? grant-record)
        grant-record

        :else
        (let [authorization (grant/authorize
                             grant-record
                             {:effect/scope (effect-scope request)
                              :usage/count 1}
                             now)
              envelope (gate/decide classification deployment-policy-pins
                                    authorization)]
          (authority-record request classification grant-record authorization
                            envelope preflight))))))
