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
  "Kubernetes reads, dry-run, mutation, and reconciliation probes."
  (:require
   [ai.miniforge.phase-deployment.shell :as shell]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} RollbackInfo
  [:map
   [:revision {:optional true} [:maybe :string]]
   [:image {:optional true} [:maybe :string]]
   [:replicas {:optional true} [:maybe int?]]])

(defn- ^{:stratum 0} pod-ready?
  [pod]
  (every? :ready (get-in pod [:status :containerStatuses] [])))

(defn ^{:stratum 0} apply!
  "Build and apply one Kustomize target through the shell adapter."
  [{:keys [kustomize-dir namespace context]}]
  (shell/kustomize-apply! kustomize-dir
                          :namespace namespace
                          :context context))

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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} rollback-info!
  "Read the pre-effect deployment state used to recover a partial mutation."
  [{:keys [deployment-name namespace context]}]
  (let [result (shell/kubectl! "get"
                               :namespace namespace
                               :context context
                               :output "json"
                               :extra-args ["deployment" deployment-name])]
    (when (schema/succeeded? result)
      (schema/validate-anomaly
       RollbackInfo
       {:revision (get-in result [:parsed :metadata :annotations
                                  "deployment.kubernetes.io/revision"])
        :image (get-in result [:parsed :spec :template :spec
                               :containers 0 :image])
        :replicas (get-in result [:parsed :status :readyReplicas])}))))

(defn ^{:stratum 1} pod-state
  [pods]
  (let [summaries (mapv (fn [pod]
                          {:name (get-in pod [:metadata :name])
                           :phase (get-in pod [:status :phase])
                           :ready? (pod-ready? pod)
                           :images (mapv :image
                                         (get-in pod [:spec :containers] []))})
                        pods)]
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
        observed {:deployment/name deployment-name
                  :deployment/ready? (schema/succeeded? rollout)
                  :deployment/pods (pod-state
                                    (get-in pods [:parsed :items] []))
                  :deployment/failure (when (schema/failed? rollout)
                                        (:stderr rollout))}]
    {:provider/observed observed
     :provider/matched? (:deployment/ready? observed)}))
