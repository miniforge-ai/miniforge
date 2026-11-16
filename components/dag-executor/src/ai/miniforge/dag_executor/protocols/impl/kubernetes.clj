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
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes
  "Kubernetes executor implementation.

   Provides task isolation via Kubernetes Jobs. Each task gets its own
   Job/Pod with configurable resource limits and environment variables.

   The executor creates a Job that runs a sleep container, then uses
   kubectl exec to run commands in it. This allows interactive execution
   similar to Docker containers.

   The canonical job configuration is documented in:
   resources/executor/kubernetes/job-template.yaml"
  (:require
   [ai.miniforge.dag-executor.protocols.impl.kubernetes-client :as client]
   [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-resources :as resources]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.workspace :as workspace]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Configuration
;; ============================================================================
(def ^{:stratum 0} default-namespace "default")

(def ^{:stratum 0} default-image "alpine:latest")

;; Resource path for job template (for documentation/reference)
(def ^{:stratum 0} job-template-resource "executor/kubernetes/job-template.yaml")

;; ============================================================================
;; Job Spec Generation
;; ============================================================================
(defn ^{:stratum 0} build-job-spec
  "Build a Kubernetes Job spec for task execution.

   Creates a Job with a single container that runs 'sleep infinity' to keep
   the pod alive for interactive command execution.

   The spec matches the canonical template at:
   resources/executor/kubernetes/job-template.yaml"
  [job-name namespace image workdir env-map resources task-id]
  {:apiVersion "batch/v1"
   :kind "Job"
   :metadata {:name job-name
              :namespace namespace
              :labels {:app "miniforge"
                       :component "task-executor"
                       :task-id (str task-id)}
              :annotations {:miniforge.ai/task-id (str task-id)
                            :miniforge.ai/created-by "dag-executor"}}
   :spec {:ttlSecondsAfterFinished 300  ; Cleanup completed jobs after 5 min
          :backoffLimit 0               ; No retries - handled at DAG scheduler level
          :completions 1
          :parallelism 1
          :template
          {:metadata {:labels {:app "miniforge"
                               :component "task-executor"
                               :job-name job-name}}
           :spec {:restartPolicy "Never"
                  :terminationGracePeriodSeconds 10
                  :containers
                  [{:name "task"
                    :image image
                    :imagePullPolicy "IfNotPresent"
                    :workingDir (or workdir resources/default-workdir)
                    :command ["sh" "-c" "trap 'exit 0' TERM; sleep infinity & wait"]
                    :env (resources/build-env-vars env-map)
                    :resources (resources/build-resource-spec resources)
                    :securityContext {:runAsNonRoot false
                                      :readOnlyRootFilesystem false
                                      :allowPrivilegeEscalation false
                                      :capabilities {:drop ["ALL"]}}
                    :volumeMounts [{:name "workspace"
                                    :mountPath "/workspace"}]}]
                  :volumes [{:name "workspace"
                             :emptyDir {:sizeLimit "1Gi"}}]}}}})

;; ============================================================================
;; Kubernetes Operations
;; ============================================================================
(defn ^{:stratum 0} cluster-info
  "Get Kubernetes cluster info."
  [kubectl-path]
  (let [result (client/run-kubectl kubectl-path "cluster-info" "--request-timeout=5s")]
    (if (zero? (:exit result))
      {:available? true
       :cluster-info (subs (:out result) 0 (min 200 (count (:out result))))}
      {:available? false
       :reason (:err result)})))

(defn ^{:stratum 0} apply-job
  "Create a Job from a spec using kubectl apply."
  [kubectl-path namespace job-spec]
  (let [spec-json (json/generate-string job-spec)
        result (shell/sh (or kubectl-path "kubectl")
                         "apply" "-n" namespace "-f" "-"
                         :in spec-json)]
    (if (zero? (:exit result))
      (result/ok {:applied true})
      (result/err :job-apply-failed (:err result)))))

(defn ^{:stratum 0} exec-in-pod
  "Execute a command in a pod."
  [kubectl-path namespace pod-name command _opts]
  (let [cmd-args (if (string? command)
                   ["sh" "-c" command]
                   command)
        full-args (concat ["exec" pod-name "-n" namespace "--"]
                          cmd-args)
        start-time (System/currentTimeMillis)
        result (apply client/run-kubectl kubectl-path full-args)]
    (result/ok {:exit-code (:exit result)
                :stdout (:out result)
                :stderr (:err result)
                :duration-ms (- (System/currentTimeMillis) start-time)})))

(defn ^{:stratum 0} copy-to-pod
  "Copy files from host to pod."
  [kubectl-path namespace pod-name local-path remote-path]
  (let [result (client/run-kubectl kubectl-path "cp" local-path
                            (str namespace "/" pod-name ":" remote-path))]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes -1})
      (result/err :copy-failed (:err result)))))

(defn ^{:stratum 0} copy-from-pod
  "Copy files from pod to host."
  [kubectl-path namespace pod-name remote-path local-path]
  (let [result (client/run-kubectl kubectl-path "cp"
                            (str namespace "/" pod-name ":" remote-path)
                            local-path)]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes -1})
      (result/err :copy-failed (:err result)))))

(defn ^{:stratum 0} delete-job
  "Delete a job and its pods."
  [kubectl-path namespace job-name]
  (let [result (client/run-kubectl kubectl-path
                            "delete" "job" job-name "-n" namespace
                            "--grace-period=5")]
    (result/ok {:released? (zero? (:exit result))})))

(defn ^{:stratum 0} get-job-status
  "Get the status of a job."
  [kubectl-path namespace job-name]
  (let [result (client/run-kubectl kubectl-path
                            "get" "job" job-name "-n" namespace
                            "-o" "jsonpath={.status.conditions[0].type}")]
    (if (zero? (:exit result))
      (result/ok {:status (case (str/trim (:out result))
                            "Complete" :stopped
                            "Failed" :error
                            "" :running
                            :unknown)
                  :raw (:out result)})
      (result/ok {:status :unknown :error (:err result)}))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Helper Functions
;; ============================================================================
(defn ^{:stratum 1} load-job-template
  "Load the job template YAML from resources.
   Returns the template string or nil if not found.
   Useful for documentation and debugging."
  []
  (when-let [resource (io/resource job-template-resource)]
    (slurp resource)))

;; ============================================================================
;; KubernetesExecutor Record
;; ============================================================================
(defrecord ^{:stratum 1} KubernetesExecutor [config namespace image kubectl-path
                               ;; Runtime state: job-name -> pod-name mapping
                               pod-cache]
  proto/TaskExecutor

  (executor-type [_this] :kubernetes)

  (available? [_this]
    (try
      (result/ok (cluster-info kubectl-path))
      (catch Exception e
        (result/ok {:available? false :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    (let [job-name (str "miniforge-task-" (subs (str task-id) 0 8))
          workdir (get env-config :workdir "/workspace")
          job-spec (build-job-spec job-name namespace image workdir
                                   (:env env-config)
                                   (:resources env-config)
                                   task-id)
          apply-result (apply-job kubectl-path namespace job-spec)]
      (if (result/err? apply-result)
        apply-result
        (let [wait-result (client/wait-for-pod-ready kubectl-path namespace job-name
                                                   client/pod-ready-timeout-ms)]
          (if (result/err? wait-result)
            (do
              (delete-job kubectl-path namespace job-name)
              wait-result)
            (let [pod-name (:pod-name (:data wait-result))]
              (swap! pod-cache assoc job-name pod-name)
              (result/ok (proto/create-environment-record
                          job-name :kubernetes task-id workdir
                          (assoc env-config
                                 :metadata {:job-name job-name
                                            :pod-name pod-name
                                            :namespace namespace})))))))))

  (execute! [_this environment-id command opts]
    (try
      (if-let [pod-name (get @pod-cache environment-id)]
        (exec-in-pod kubectl-path namespace pod-name command opts)
        ;; Pod name not in cache, try to look it up
        (let [pod-result (client/get-pod-for-job kubectl-path namespace environment-id)]
          (if (result/ok? pod-result)
            (let [pod-name (:pod-name (:data pod-result))]
              (swap! pod-cache assoc environment-id pod-name)
              (exec-in-pod kubectl-path namespace pod-name command opts))
            pod-result)))
      (catch Exception e
        (result/err :exec-failed (.getMessage e)))))

  (copy-to! [_this environment-id local-path remote-path]
    (try
      (if-let [pod-name (get @pod-cache environment-id)]
        (copy-to-pod kubectl-path namespace pod-name local-path remote-path)
        (result/err :pod-not-found "Pod not in cache"))
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (copy-from! [_this environment-id remote-path local-path]
    (try
      (if-let [pod-name (get @pod-cache environment-id)]
        (copy-from-pod kubectl-path namespace pod-name remote-path local-path)
        (result/err :pod-not-found "Pod not in cache"))
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (release-environment! [_this environment-id]
    (try
      ;; Remove from cache
      (swap! pod-cache dissoc environment-id)
      ;; Delete the job (will also delete pods)
      (delete-job kubectl-path namespace environment-id)
      (catch Exception e
        (result/err :release-failed (.getMessage e)))))

  (environment-status [_this environment-id]
    (try
      (get-job-status kubectl-path namespace environment-id)
      (catch Exception e
        (result/ok {:status :unknown :error (.getMessage e)}))))

  ;; K8s persistence: git push (same as Docker). Fleet tier will use object store.
  (persist-workspace! [this environment-id opts]
    (let [exec! (fn [cmd] (proto/execute! this environment-id cmd
                                          {:workdir (get opts :workdir "/workspace")}))]
      (workspace/git-persist! exec! opts)))

  (restore-workspace! [this environment-id opts]
    (let [exec! (fn [cmd] (proto/execute! this environment-id cmd
                                          {:workdir (get opts :workdir "/workspace")}))]
      (workspace/git-restore! exec! opts))))

;------------------------------------------------------------------------------ Layer 2

;; ============================================================================
;; Factory
;; ============================================================================
(defn ^{:stratum 2} create-kubernetes-executor
  "Create a Kubernetes executor.

   Config:
   - :namespace - K8s namespace (default: 'default')
   - :image - Container image for tasks (default: alpine:latest)
   - :kubectl-path - Path to kubectl binary"
  [config]
  (map->KubernetesExecutor
   {:config config
    :namespace (get config :namespace default-namespace)
    :image (get config :image default-image)
    :kubectl-path (:kubectl-path config)
    :pod-cache (atom {})}))
