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
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [cheshire.core :as json]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-namespace "default")
(def default-image "alpine:latest")
(def default-workdir "/workspace")
(def pod-ready-timeout-ms 120000)
(def pod-ready-poll-interval-ms 2000)

;; Default resource limits (matches job-template.yaml)
(def default-resources
  {:limits {:memory "512Mi" :cpu "500m"}
   :requests {:memory "256Mi" :cpu "100m"}})

;; Resource path for job template (for documentation/reference)
(def job-template-resource "executor/kubernetes/job-template.yaml")

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- kubectl-cmd
  "Build kubectl command with optional custom path."
  [kubectl-path & args]
  (apply vector (or kubectl-path "kubectl") args))

(defn- run-kubectl
  "Execute a kubectl command and return the result."
  [kubectl-path & args]
  (try
    (apply shell/sh (apply kubectl-cmd kubectl-path args))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn- build-env-vars
  "Build K8s env var specs from a map."
  [env-map]
  (mapv (fn [[k v]] {:name (name k) :value (str v)}) env-map))

(defn- build-resource-spec
  "Build K8s resource limits/requests spec.
   Merges provided resources with defaults."
  [resources]
  (let [merged (merge-with merge default-resources resources)]
    {:limits (cond-> {}
               (get-in merged [:limits :memory])
               (assoc :memory (get-in merged [:limits :memory]))
               (get-in merged [:limits :cpu])
               (assoc :cpu (str (get-in merged [:limits :cpu]))))
     :requests (cond-> {}
                 (get-in merged [:requests :memory])
                 (assoc :memory (get-in merged [:requests :memory]))
                 (get-in merged [:requests :cpu])
                 (assoc :cpu (str (get-in merged [:requests :cpu]))))}))

(defn load-job-template
  "Load the job template YAML from resources.
   Returns the template string or nil if not found.
   Useful for documentation and debugging."
  []
  (when-let [resource (io/resource job-template-resource)]
    (slurp resource)))

;; ============================================================================
;; Job Spec Generation
;; ============================================================================

(defn build-job-spec
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
                    :workingDir (or workdir default-workdir)
                    :command ["sh" "-c" "trap 'exit 0' TERM; sleep infinity & wait"]
                    :env (build-env-vars env-map)
                    :resources (build-resource-spec resources)
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

(defn cluster-info
  "Get Kubernetes cluster info."
  [kubectl-path]
  (let [result (run-kubectl kubectl-path "cluster-info" "--request-timeout=5s")]
    (if (zero? (:exit result))
      {:available? true
       :cluster-info (subs (:out result) 0 (min 200 (count (:out result))))}
      {:available? false
       :reason (:err result)})))

(defn apply-job
  "Create a Job from a spec using kubectl apply."
  [kubectl-path namespace job-spec]
  (let [spec-json (json/generate-string job-spec)
        result (shell/sh (or kubectl-path "kubectl")
                         "apply" "-n" namespace "-f" "-"
                         :in spec-json)]
    (if (zero? (:exit result))
      (result/ok {:applied true})
      (result/err :job-apply-failed (:err result)))))

(defn get-pod-for-job
  "Get the pod name for a job."
  [kubectl-path namespace job-name]
  (let [result (run-kubectl kubectl-path
                            "get" "pods" "-n" namespace
                            "-l" (str "job-name=" job-name)
                            "-o" "jsonpath={.items[0].metadata.name}")]
    (if (and (zero? (:exit result)) (not (str/blank? (:out result))))
      (result/ok {:pod-name (str/trim (:out result))})
      (result/err :pod-not-found (or (:err result) "No pod found for job")))))

(defn wait-for-pod-ready
  "Wait for pod to be in Running state with containers ready."
  [kubectl-path namespace job-name timeout-ms]
  (let [start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        (result/err :pod-timeout "Timed out waiting for pod to be ready")
        (let [pod-result (get-pod-for-job kubectl-path namespace job-name)]
          (if (result/err? pod-result)
            ;; Pod not created yet, wait and retry
            (do
              (Thread/sleep pod-ready-poll-interval-ms)
              (recur))
            ;; Check pod status
            (let [pod-name (:pod-name (:data pod-result))
                  status-result (run-kubectl kubectl-path
                                             "get" "pod" pod-name "-n" namespace
                                             "-o" "jsonpath={.status.phase}")]
              (cond
                (= "Running" (str/trim (:out status-result)))
                (result/ok {:pod-name pod-name :status :running})

                (contains? #{"Failed" "Succeeded"} (str/trim (:out status-result)))
                (result/err :pod-failed (str "Pod ended with status: " (:out status-result)))

                :else
                (do
                  (Thread/sleep pod-ready-poll-interval-ms)
                  (recur))))))))))

(defn exec-in-pod
  "Execute a command in a pod."
  [kubectl-path namespace pod-name command _opts]
  (let [cmd-args (if (string? command)
                   ["sh" "-c" command]
                   command)
        full-args (concat ["exec" pod-name "-n" namespace "--"]
                          cmd-args)
        start-time (System/currentTimeMillis)
        result (apply run-kubectl kubectl-path full-args)]
    (result/ok {:exit-code (:exit result)
                :stdout (:out result)
                :stderr (:err result)
                :duration-ms (- (System/currentTimeMillis) start-time)})))

(defn copy-to-pod
  "Copy files from host to pod."
  [kubectl-path namespace pod-name local-path remote-path]
  (let [result (run-kubectl kubectl-path "cp" local-path
                            (str namespace "/" pod-name ":" remote-path))]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes -1})
      (result/err :copy-failed (:err result)))))

(defn copy-from-pod
  "Copy files from pod to host."
  [kubectl-path namespace pod-name remote-path local-path]
  (let [result (run-kubectl kubectl-path "cp"
                            (str namespace "/" pod-name ":" remote-path)
                            local-path)]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes -1})
      (result/err :copy-failed (:err result)))))

(defn delete-job
  "Delete a job and its pods."
  [kubectl-path namespace job-name]
  (let [result (run-kubectl kubectl-path
                            "delete" "job" job-name "-n" namespace
                            "--grace-period=5")]
    (result/ok {:released? (zero? (:exit result))})))

(defn get-job-status
  "Get the status of a job."
  [kubectl-path namespace job-name]
  (let [result (run-kubectl kubectl-path
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

;; ============================================================================
;; KubernetesExecutor Record
;; ============================================================================

(defrecord KubernetesExecutor [config namespace image kubectl-path
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
        (let [wait-result (wait-for-pod-ready kubectl-path namespace job-name
                                              pod-ready-timeout-ms)]
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
        (let [pod-result (get-pod-for-job kubectl-path namespace environment-id)]
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
        (result/ok {:status :unknown :error (.getMessage e)})))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-kubernetes-executor
  "Create a Kubernetes executor.

   Config:
   - :namespace - K8s namespace (default: 'default')
   - :image - Container image for tasks (default: alpine:latest)
   - :kubectl-path - Path to kubectl binary"
  [config]
  (map->KubernetesExecutor
   {:config config
    :namespace (or (:namespace config) default-namespace)
    :image (or (:image config) default-image)
    :kubectl-path (:kubectl-path config)
    :pod-cache (atom {})}))
