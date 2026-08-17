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
(ns ai.miniforge.phase-deployment.deploy-provider
  "Kubernetes reads, mutation, and reconciliation probes."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase-deployment.messages :as msg]
   [ai.miniforge.phase-deployment.shell :as shell]
   [ai.miniforge.schema.interface :as schema]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} RollbackInfo
  [:map
   [:revision {:optional true} [:maybe :string]]
   [:image {:optional true} [:maybe :string]]
   [:replicas {:optional true} [:maybe int?]]])

(defn ^{:stratum 0} apply!
  "Build and apply one Kustomize target through the shell adapter."
  [{:keys [kustomize-dir namespace context]}]
  (shell/kustomize-apply! kustomize-dir
                          :namespace namespace :context context))

(defn ^{:stratum 0} render!
  "Render one Kustomize target without contacting Kubernetes."
  [{:keys [kustomize-dir]}]
  (shell/kustomize-build! kustomize-dir))

(defn ^{:stratum 0} apply-rendered!
  "Apply exactly the manifest bytes recorded by the caller."
  [{:keys [namespace context]} rendered-yaml]
  (shell/kubectl-apply! rendered-yaml
                        :namespace namespace :context context))

(defn ^{:stratum 0} dry-run!
  "Ask the API server to validate exactly the manifest bytes to be applied."
  [{:keys [namespace context]} rendered-yaml]
  (shell/kubectl-apply! rendered-yaml :namespace namespace :context context
                        :server-dry-run? true))

(def ^{:stratum 0} PodState
  [:map
   [:pod-count nat-int?]
   [:ready-count nat-int?]
   [:pods
    [:vector
     [:map
      [:name {:optional true} [:maybe :string]]
      [:phase {:optional true} [:maybe :string]]
      [:ready? :boolean]
      [:images [:vector :string]]]]]])

(defn- ^{:stratum 0} observation-failure
  [rollout-result pod-result pod-state]
  (cond
    (schema/failed? rollout-result)
    (get rollout-result :stderr (get rollout-result :error))
    (schema/failed? pod-result) (get pod-result :stderr (get pod-result :error))
    (anomaly/anomaly? pod-state) (:anomaly/message pod-state)
    :else nil))

(defn- ^{:stratum 0} pod-summary
  [pod]
  (let [statuses (get-in pod [:status :containerStatuses] [])]
    {:name (get-in pod [:metadata :name])
     :phase (get-in pod [:status :phase])
     :ready? (boolean (and (seq statuses) (every? :ready statuses)))
     :images (mapv :image (get-in pod [:spec :containers] []))}))

(defn- ^{:stratum 0} target-failure
  [kubectl-result]
  (schema/failure
   :target
   (or (not-empty (:stderr kubectl-result))
       (:error kubectl-result)
       (msg/t :deploy/context-unavailable))
   {:kubectl-result kubectl-result}))

(defn- ^{:stratum 0} rollback-failure
  [kubectl-result]
  (schema/failure
   :rollback-info
   (or (not-empty (:stderr kubectl-result))
       (:error kubectl-result)
       (msg/t :deploy/rollback-capture-failed))
   {:kubectl-result kubectl-result}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} target!
  "Resolve a context-free target to kubectl's configured current context."
  [{:keys [context] :as deploy-config}]
  (if context
    (schema/success :target deploy-config)
    (let [result (shell/kubectl! "config" :extra-args ["current-context"])
          current-context (some-> (:stdout result) str/trim not-empty)]
      (cond
        (schema/failed? result) (target-failure result)
        (nil? current-context) (target-failure result)
        :else (schema/success :target
                              (assoc deploy-config :context current-context))))))

(defn ^{:stratum 1} rollback-info!
  "Read the pre-effect deployment state used to recover a partial mutation."
  [{:keys [deployment-name namespace context]}]
  (let [result (shell/kubectl! "get"
                               :namespace namespace
                               :context context
                               :output "json"
                               :extra-args ["deployment" deployment-name])]
    (if (schema/failed? result)
      (rollback-failure result)
      (schema/validate-anomaly
       RollbackInfo
       {:revision (get-in result [:parsed :metadata :annotations
                                  "deployment.kubernetes.io/revision"])
        :image (get-in result [:parsed :spec :template :spec
                               :containers 0 :image])
        :replicas (get-in result [:parsed :status :readyReplicas])}))))

(defn ^{:stratum 1} pod-state
  [pods]
  (let [summaries (mapv pod-summary pods)]
    (schema/validate-anomaly
     PodState
     {:pod-count (count summaries)
      :ready-count (count (filter :ready? summaries))
      :pods summaries})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observe!
  "Observe rollout completion and pod state for effect reconciliation."
  [{:keys [deployment-name app-label namespace context]}]
  (let [rollout (shell/kubectl-rollout-status!
                 (str "deployment/" deployment-name)
                 :namespace namespace
                 :context context
                 :timeout-s 300)
        pods (shell/kubectl-get-pods! (str "app=" app-label)
                                      :namespace namespace
                                      :context context)
        pod-state (pod-state (get-in pods [:parsed :items] []))
        failure (observation-failure rollout pods pod-state)
        observed {:deployment/name deployment-name
                  :deployment/ready? (nil? failure)
                  :deployment/pods pod-state
                  :deployment/failure failure}]
    {:provider/observed observed
     :provider/matched? (:deployment/ready? observed)}))
