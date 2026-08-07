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
(ns ai.miniforge.phase-deployment.deploy
  "Deploy phase interceptor."
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase-deployment.defaults :as defaults]
            [ai.miniforge.phase-deployment.deploy-provider :as provider]
            [ai.miniforge.phase-deployment.deploy-result :as result]
            [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

;; Defaults + schemas
(def ^{:stratum 0} default-config
  "Deploy phase defaults loaded from EDN."
  (defaults/phase-defaults :deploy))

(def ^{:stratum 0} DeployRunConfig
  [:map
   [:phase-config map?]
   [:kustomize-dir :string]
   [:namespace :string]
   [:app-label :string]
   [:deployment-name :string]
   [:context {:optional true} [:maybe :string]]])

(def ^{:stratum 0} RollbackInfo
  [:map
   [:revision {:optional true} [:maybe :string]]
   [:image {:optional true} [:maybe :string]]
   [:replicas {:optional true} [:maybe int?]]])

(defn- ^{:stratum 0} validate!
  [result-schema value]
  (schema/validate-anomaly result-schema value))

;; Shared helpers
(defn- ^{:stratum 0} get-logger
  "Resolve logger from ctx, creating a default if absent."
  [ctx]
  (or (get-in ctx [:execution/logger])
      (log/create-logger {:min-level :info :output :human})))

(defn- ^{:stratum 0} failed-enter
  "Build a :failed phase context for enter-time failures."
  [ctx start-time result-map]
  (-> ctx
      (assoc-in [:phase :name] :deploy)
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :result] result-map)))

(defn- ^{:stratum 0} merged-phase-config
  [ctx phase-kw]
  (phase/merge-with-defaults
   (assoc (or (get-in ctx [:phase-config]) {}) :phase phase-kw)))

(defn ^{:stratum 0} leave-deploy
  "Post-deploy: record final metrics."
  [ctx]
  (if (= :completed (get-in ctx [:phase :status]))
    ctx
    (assoc-in ctx [:phase :status] :failed)))

(defn- ^{:stratum 0} deployment-outcome
  [config rollback-info applied]
  (let [rendered-yaml (:rendered-yaml applied)]
    (if (schema/failed? applied)
      {:deploy/status :failed
       :deploy/stage :apply
       :deploy/rollback-info rollback-info
       :deploy/rendered-yaml rendered-yaml
       :deploy/failure (or (:error applied)
                           (get-in applied [:apply-result :stderr]))}
      (let [observation (provider/observe! config)
            observed (:provider/observed observation)]
        {:deploy/status (if (:provider/matched? observation)
                          :success
                          :failed)
         :deploy/stage :observe
         :deploy/rollback-info rollback-info
         :deploy/rendered-yaml rendered-yaml
         :deploy/pod-state (:deployment/pods observed)
         :deploy/failure (:deployment/failure observed)}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-deploy-config
  [ctx]
  (let [phase-config  (merged-phase-config ctx :deploy)
        input         (or (get-in ctx [:execution/input]) {})
        prev-outputs  (or (get-in ctx [:execution/phase-results :provision :result :outputs]) {})
        app-label     (get input :app-label
                           (get phase-config :app-label "ixi"))
        deploy-config {:phase-config    phase-config
                       :kustomize-dir   (or (get input :kustomize-dir)
                                            (get phase-config :kustomize-dir))
                       :namespace       (get input :namespace
                                             (get phase-config :namespace "default"))
                       :context         (or (get input :context)
                                            (get phase-config :context)
                                            (get prev-outputs :gke_context))
                       :app-label       app-label
                       :deployment-name (get input :deployment-name
                                             (get phase-config :deployment-name app-label))}]
    (validate! DeployRunConfig deploy-config)))

(defn- ^{:stratum 1} capture-current-state
  "Capture current deployment state for rollback evidence."
  [deployment-name namespace context]
  (let [result (shell/kubectl! "get"
                               :namespace namespace
                               :context context
                               :output "json"
                               :extra-args ["deployment" deployment-name])]
    (when (schema/succeeded? result)
      (validate!
       RollbackInfo
       {:revision (get-in result [:parsed :metadata :annotations "deployment.kubernetes.io/revision"])
        :image    (get-in result [:parsed :spec :template :spec :containers 0 :image])
        :replicas (get-in result [:parsed :status :readyReplicas])}))))

(defn- ^{:stratum 1} invalid-config-result
  [ctx start-time ex]
  (failed-enter ctx start-time
                {:status :error
                 :error  (msg/t :deploy/invalid-config
                                {:error (ex-message ex)})}))

(defn ^{:stratum 1} error-deploy
  "Handle deploy phase errors."
  [ctx ex]
  (let [logger (get-logger ctx)]
    (log/error logger :deploy :deploy/error
               {:data {:message (ex-message ex)
                       :data    (ex-data ex)}})
    (-> ctx
        (assoc-in [:phase :status] :failed)
        (update :execution/errors (fnil conj [])
                {:type    :deploy-error
                 :phase   :deploy
                 :message (ex-message ex)
                 :data    (ex-data ex)}))))

;------------------------------------------------------------------------------ Layer 2

;; Phase interceptors + registration
(defn ^{:stratum 2} enter-deploy
  "Execute deployment: build manifests, apply them, and wait for rollout."
  [ctx]
  (let [start-time (System/currentTimeMillis)
        logger     (get-logger ctx)]
    (try
      (let [config            (resolve-deploy-config ctx)
            rollback-info     (capture-current-state (:deployment-name config)
                                                    (:namespace config)
                                                    (:context config))
            applied           (shell/kustomize-apply! (:kustomize-dir config)
                                                      :namespace (:namespace config)
                                                      :context (:context config))]
        (result/store-deployment ctx start-time logger config
                                 (deployment-outcome config rollback-info
                                                     applied)))
      (catch clojure.lang.ExceptionInfo ex
        (invalid-config-result ctx start-time ex)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (enter-deploy {:execution/input {:kustomize-dir "/path/to/overlay"
                                   :namespace "ixi"
                                   :app-label "ixi"}})
  :leave-this-here)
