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

(ns ai.miniforge.phase.deploy.deploy
  "Deploy phase interceptor."
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.deploy.defaults :as defaults]
            [ai.miniforge.phase.deploy.evidence :as evidence]
            [ai.miniforge.phase.deploy.messages :as msg]
            [ai.miniforge.phase.deploy.shell :as shell]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults + schemas

(def default-config
  "Deploy phase defaults loaded from EDN."
  (defaults/phase-defaults :deploy))

(def DeployRunConfig
  [:map
   [:phase-config map?]
   [:kustomize-dir :string]
   [:namespace :string]
   [:app-label :string]
   [:deployment-name :string]
   [:context {:optional true} [:maybe :string]]])

(def RollbackInfo
  [:map
   [:revision {:optional true} [:maybe :string]]
   [:image {:optional true} [:maybe :string]]
   [:replicas {:optional true} [:maybe int?]]])

(def PodSummary
  [:map
   [:name {:optional true} [:maybe :string]]
   [:phase {:optional true} [:maybe :string]]
   [:ready? :boolean]
   [:images [:vector :string]]])

(def PodState
  [:map
   [:pod-count nat-int?]
   [:ready-count nat-int?]
   [:pods [:vector PodSummary]]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

;------------------------------------------------------------------------------ Layer 1
;; Shared helpers

(defn- get-logger
  "Resolve logger from ctx, creating a default if absent."
  [ctx]
  (or (get-in ctx [:execution/logger])
      (log/create-logger {:min-level :info :output :human})))

(defn- failed-enter
  "Build a :failed phase context for enter-time failures."
  [ctx start-time result-map]
  (-> ctx
      (assoc-in [:phase :name] :deploy)
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :result] result-map)))

(defn- merged-phase-config
  [ctx phase-kw]
  (registry/merge-with-defaults
   (assoc (or (get-in ctx [:phase-config]) {}) :phase phase-kw)))

(defn- resolve-deploy-config
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

(defn- pod-ready?
  [pod]
  (every? :ready (get-in pod [:status :containerStatuses] [])))

(defn- pod-summary
  [pod]
  {:name   (get-in pod [:metadata :name])
   :phase  (get-in pod [:status :phase])
   :ready? (pod-ready? pod)
   :images (into [] (map :image) (get-in pod [:spec :containers] []))})

(defn- build-pod-state
  [pods]
  (let [summaries (into [] (map pod-summary) pods)]
    (validate!
     PodState
     {:pod-count   (count summaries)
      :ready-count (count (filter :ready? summaries))
      :pods        summaries})))

(defn- capture-current-state
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

(defn- capture-pod-state
  "Capture post-deploy pod state for evidence and gate checking."
  [app-label namespace context]
  (let [result (shell/kubectl-get-pods! (str "app=" app-label)
                                        :namespace namespace
                                        :context context)]
    (when (schema/succeeded? result)
      (build-pod-state (get-in result [:parsed :items] [])))))

(defn- rollout-metrics
  [start-time pod-state]
  {:duration-ms (- (System/currentTimeMillis) start-time)
   :pod-count   (:pod-count pod-state)
   :ready-count (:ready-count pod-state)})

(defn- add-deploy-evidence
  [ctx rollback-evidence manifest-evidence image-evidence]
  (cond-> ctx
    rollback-evidence (evidence/add-evidence-to-ctx rollback-evidence)
    true (evidence/add-evidence-to-ctx manifest-evidence)
    true (evidence/add-evidence-to-ctx image-evidence)))

(defn- store-apply-failure
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

(defn- store-rollout-failure
  [ctx start-time logger rollout-result manifest-evidence image-evidence]
  (log/error logger :deploy :deploy/rollout-failed
             {:data {:stderr (:stderr rollout-result)}})
  (-> (failed-enter ctx start-time
                    {:status  :rollout-failed
                     :error   (:stderr rollout-result)
                     :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}})
      (evidence/add-evidence-to-ctx manifest-evidence)
      (evidence/add-evidence-to-ctx image-evidence)))

(defn- store-successful-deploy
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

(defn- invalid-config-result
  [ctx start-time ex]
  (failed-enter ctx start-time
                {:status :error
                 :error  (msg/t :deploy/invalid-config
                                {:error (ex-message ex)})}))

;------------------------------------------------------------------------------ Layer 2
;; Phase interceptors + registration

(defn enter-deploy
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
            (let [rollout-result (shell/kubectl-rollout-status!
                                  (str "deployment/" (:deployment-name config))
                                  :namespace (:namespace config)
                                  :context (:context config)
                                  :timeout-s 300)
                  pod-state      (or (capture-pod-state (:app-label config)
                                                        (:namespace config)
                                                        (:context config))
                                     (build-pod-state []))]
              (if (schema/failed? rollout-result)
                (store-rollout-failure ctx
                                       start-time
                                       logger
                                       rollout-result
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

(defn leave-deploy
  "Post-deploy: record final metrics."
  [ctx]
  (if (= :completed (get-in ctx [:phase :status]))
    ctx
    (assoc-in ctx [:phase :status] :failed)))

(defn error-deploy
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

;; Registration

(defmethod registry/get-phase-interceptor :deploy
  [_]
  {:name   :deploy
   :enter  enter-deploy
   :leave  leave-deploy
   :error  error-deploy
   :config default-config})

;------------------------------------------------------------------------------ Rich Comment
(comment
  (enter-deploy {:execution/input {:kustomize-dir "/path/to/overlay"
                                   :namespace "ixi"
                                   :app-label "ixi"}})
  :leave-this-here)
