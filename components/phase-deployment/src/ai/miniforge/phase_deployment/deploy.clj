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
            [ai.miniforge.phase-deployment.evidence :as evidence]
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

(defn- ^{:stratum 0} rollout-metrics
  [start-time pod-state]
  {:duration-ms (- (System/currentTimeMillis) start-time)
   :pod-count   (:pod-count pod-state)
   :ready-count (:ready-count pod-state)})

(defn- ^{:stratum 0} add-deploy-evidence
  [ctx rollback-evidence manifest-evidence image-evidence]
  (cond-> ctx
    rollback-evidence (evidence/add-evidence-to-ctx rollback-evidence)
    true (evidence/add-evidence-to-ctx manifest-evidence)
    true (evidence/add-evidence-to-ctx image-evidence)))

(defn ^{:stratum 0} leave-deploy
  "Post-deploy: record final metrics."
  [ctx]
  (if (= :completed (get-in ctx [:phase :status]))
    ctx
    (assoc-in ctx [:phase :status] :failed)))

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

(defn- ^{:stratum 1} store-apply-failure
  [ctx start-time logger result]
  (log/error logger :deploy :deploy/apply-failed
             {:data {:error (:error result)
                     :build-stderr (get-in result [:build-result :stderr])
                     :apply-stderr (get-in result [:apply-result :stderr])}})
  (failed-enter ctx start-time
                {:status  :error
                 :error   (or (get result :error)
                              (get-in result [:apply-result :stderr]))
                 :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}}))

(defn- ^{:stratum 1} store-rollout-failure
  [ctx start-time logger failure rollback-evidence manifest-evidence image-evidence]
  (log/error logger :deploy :deploy/rollout-failed
             {:data {:stderr failure}})
  (-> (failed-enter ctx start-time
                    {:status  :rollout-failed
                     :error   failure
                     :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}})
      (add-deploy-evidence rollback-evidence manifest-evidence image-evidence)))

(defn- ^{:stratum 1} store-successful-deploy
  [ctx start-time config rollback-evidence manifest-evidence image-evidence image-digests pod-state logger]
  (let [metrics (rollout-metrics start-time pod-state)]
    (log/info logger :deploy :deploy/complete
              {:data metrics})
    (-> ctx
        (assoc-in [:phase :name] :deploy)
        (assoc-in [:phase :gates] (get-in config [:phase-config :gates]))
        (assoc-in [:phase :budget] (get-in config [:phase-config :budget]))
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :result]
                  {:status   :success
                   :output   {:pod-state pod-state
                              :images    image-digests}
                   :artifact {:content   pod-state
                              :type      :deployment-state
                              :app-label (:app-label config)
                              :namespace (:namespace config)}
                   :metrics  metrics})
        (add-deploy-evidence rollback-evidence manifest-evidence image-evidence))))

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
            rollback-evidence (when rollback-info
                                (evidence/create-evidence
                                 :evidence/rollback-info
                                 rollback-info
                                 {:deployment (:deployment-name config)
                                  :namespace (:namespace config)}))
            result            (shell/kustomize-apply! (:kustomize-dir config)
                                                      :namespace (:namespace config)
                                                      :context (:context config))]
        (if (schema/failed? result)
          (store-apply-failure ctx start-time logger result)
          (let [rendered-yaml     (:rendered-yaml result)
                manifest-evidence (evidence/create-evidence
                                   :evidence/rendered-manifests
                                   rendered-yaml
                                   {:kustomize-dir (:kustomize-dir config)})
                image-digests     (evidence/extract-image-digests rendered-yaml)
                image-evidence    (evidence/create-evidence
                                   :evidence/image-digests
                                   image-digests)]
            (log/info logger :deploy :deploy/applied
                      {:data {:image-count (count image-digests)}})
            (let [observation (provider/observe! config)
                  observed (:provider/observed observation)
                  pod-state (:deployment/pods observed)]
              (if-not (:provider/matched? observation)
                (store-rollout-failure ctx
                                       start-time
                                       logger
                                       (:deployment/failure observed)
                                       rollback-evidence
                                       manifest-evidence
                                       image-evidence)
                (store-successful-deploy ctx
                                         start-time
                                         config
                                         rollback-evidence
                                         manifest-evidence
                                         image-evidence
                                         image-digests
                                         pod-state
                                         logger))))))
      (catch clojure.lang.ExceptionInfo ex
        (invalid-config-result ctx start-time ex)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (enter-deploy {:execution/input {:kustomize-dir "/path/to/overlay"
                                   :namespace "ixi"
                                   :app-label "ixi"}})
  :leave-this-here)
