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
  "Deploy phase interceptor.

   Deploys application to Kubernetes via Kustomize:
   1. kustomize build → captures rendered manifests
   2. kubectl apply → applies manifests
   3. kubectl rollout status → waits for rollout completion
   4. Captures pod/service state as evidence

   Agent: none (CLI tool execution, no LLM)
   Default gates: [:deploy-healthy]"
  (:require [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.deploy.evidence :as evidence]
            [ai.miniforge.phase.deploy.shell :as shell]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults — overridable via workflow EDN."
  {:gates  [:deploy-healthy]
   :budget {:tokens 0 :iterations 3 :time-seconds 300}
   :agent  nil})

(registry/register-phase-defaults! :deploy default-config)

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

;; State capture helpers

(defn- capture-current-state
  "Capture current deployment state for rollback evidence."
  [deployment-name namespace context _logger]
  (let [result (shell/kubectl! "get"
                               :namespace namespace
                               :context context
                               :output "json"
                               :extra-args ["deployment" deployment-name])]
    (when (schema/succeeded? result)
      (let [parsed (:parsed result)]
        {:revision  (get-in parsed [:metadata :annotations "deployment.kubernetes.io/revision"])
         :image     (get-in parsed [:spec :template :spec :containers 0 :image])
         :replicas  (get-in parsed [:status :readyReplicas])}))))

(defn- capture-pod-state
  "Capture post-deploy pod state for evidence and gate checking."
  [app-label namespace context]
  (let [result (shell/kubectl-get-pods! (str "app=" app-label)
                                        :namespace namespace
                                        :context context)]
    (when (schema/succeeded? result)
      (let [pods (get-in result [:parsed :items] [])]
        {:pod-count    (count pods)
         :ready-count  (count (filter (fn [pod]
                                        (every? :ready
                                                (get-in pod [:status :containerStatuses] [])))
                                      pods))
         :pods         (mapv (fn [pod]
                               {:name   (get-in pod [:metadata :name])
                                :phase  (get-in pod [:status :phase])
                                :ready? (every? :ready
                                                (get-in pod [:status :containerStatuses] []))
                                :images (mapv :image
                                              (get-in pod [:spec :containers] []))})
                             pods)}))))

;------------------------------------------------------------------------------ Layer 2
;; Phase interceptors + registration

(defn enter-deploy
  "Execute deployment: build manifests → apply → wait for rollout.

   Reads deployment config from execution input:
     :kustomize-dir  - Directory containing kustomization.yaml
     :namespace      - Target K8s namespace
     :context        - K8s context name (for multi-cluster)
     :app-label      - App label for pod selection
     :deployment-name - Deployment name for rollout status"
  [ctx]
  (let [config      (registry/merge-with-defaults (get-in ctx [:phase-config]) :deploy)
        start-time  (System/currentTimeMillis)
        logger      (get-logger ctx)
        input       (get-in ctx [:execution/input])
        ;; Merge provision outputs (from previous phase) into deploy config
        prev-outputs (get-in ctx [:execution/phase-results :provision :result :outputs] {})
        kustomize-dir   (or (:kustomize-dir input) (:kustomize-dir config))
        namespace       (or (:namespace input) (:namespace config) "default")
        context         (or (:context input) (:context config)
                            (:gke_context prev-outputs))
        app-label       (or (:app-label input) (:app-label config) "ixi")
        deployment-name (or (:deployment-name input) (:deployment-name config) app-label)]

    (log/info logger :deploy :deploy/starting
              {:data {:kustomize-dir kustomize-dir
                      :namespace namespace
                      :deployment deployment-name}})

    ;; Step 0: Capture current state for rollback evidence
    (let [rollback-info     (capture-current-state deployment-name namespace context logger)
          rollback-evidence (when rollback-info
                              (evidence/create-evidence
                               :evidence/rollback-info rollback-info
                               {:deployment deployment-name :namespace namespace}))
          ;; Step 1: Kustomize build + apply
          result            (shell/kustomize-apply! kustomize-dir
                                                   :namespace namespace
                                                   :context context)]
      (if (schema/failed? result)
          ;; Build or apply failed
          (do
            (log/error logger :deploy :deploy/apply-failed
                       {:data {:error (:error result)
                               :build-stderr (get-in result [:build-result :stderr])
                               :apply-stderr (get-in result [:apply-result :stderr])}})
            (failed-enter ctx start-time
                          {:status :error
                           :error  (or (:error result)
                                       (get-in result [:apply-result :stderr]))
                           :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}}))

          ;; Step 2: Capture rendered manifests as evidence
          (let [rendered-yaml (:rendered-yaml result)
                manifest-evidence (evidence/create-evidence
                                   :evidence/rendered-manifests
                                   rendered-yaml
                                   {:kustomize-dir kustomize-dir})
                image-digests (evidence/extract-image-digests rendered-yaml)
                image-evidence (evidence/create-evidence
                                :evidence/image-digests
                                image-digests)]

            (log/info logger :deploy :deploy/applied
                      {:data {:image-count (count image-digests)}})

            ;; Step 3: Wait for rollout completion
            (let [rollout-result (shell/kubectl-rollout-status!
                                  (str "deployment/" deployment-name)
                                  :namespace namespace
                                  :context context
                                  :timeout-s 300)]
              (if (schema/failed? rollout-result)
                ;; Rollout failed/timed out
                (do
                  (log/error logger :deploy :deploy/rollout-failed
                             {:data {:stderr (:stderr rollout-result)}})
                  (-> (failed-enter ctx start-time
                                    {:status :rollout-failed
                                     :error  (:stderr rollout-result)
                                     :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}})
                      (evidence/add-evidence-to-ctx manifest-evidence)
                      (evidence/add-evidence-to-ctx image-evidence)))

                ;; Step 4: Capture post-deploy pod state
                (let [pod-state (capture-pod-state app-label namespace context)
                      duration  (- (System/currentTimeMillis) start-time)]
                  (log/info logger :deploy :deploy/complete
                            {:data {:pod-count (:pod-count pod-state)
                                    :ready-count (:ready-count pod-state)
                                    :duration-ms duration}})
                  (-> ctx
                      (assoc-in [:phase :name] :deploy)
                      (assoc-in [:phase :gates] (:gates config))
                      (assoc-in [:phase :budget] (:budget config))
                      (assoc-in [:phase :started-at] start-time)
                      (assoc-in [:phase :status] :completed)
                      (assoc-in [:phase :result]
                                {:status    :success
                                 :output    {:pod-state pod-state
                                             :images    image-digests}
                                 :artifact  {:content   pod-state
                                             :type      :deployment-state
                                             :app-label app-label
                                             :namespace namespace}
                                 :metrics   {:duration-ms duration
                                             :pod-count   (:pod-count pod-state)
                                             :ready-count (:ready-count pod-state)}})
                      (cond-> rollback-evidence (evidence/add-evidence-to-ctx rollback-evidence))
                      (evidence/add-evidence-to-ctx manifest-evidence)
                      (evidence/add-evidence-to-ctx image-evidence))))))))))

(defn leave-deploy
  "Post-deploy: record final metrics."
  [ctx]
  (let [status (get-in ctx [:phase :status])]
    (if (= :completed status)
      ctx
      ;; If failed, ensure status is propagated
      (assoc-in ctx [:phase :status] :failed))))

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
  ;; Test deploy phase (requires K8s cluster access)
  ;; (enter-deploy {:execution/input {:kustomize-dir "/path/to/overlay"
  ;;                                  :namespace "ixi"
  ;;                                  :app-label "ixi"}})

  :leave-this-here)
