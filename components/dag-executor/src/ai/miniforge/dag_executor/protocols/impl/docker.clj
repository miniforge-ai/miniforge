(ns ai.miniforge.dag-executor.protocols.impl.docker
  "Docker executor implementation.

   Provides task isolation via Docker containers. Each task gets its own
   container with configurable resource limits and environment variables.

   Pre-built task runner images are available at:
   - resources/executor/docker/Dockerfile.task-runner       (minimal Alpine)
   - resources/executor/docker/Dockerfile.task-runner-clojure (with Clojure tooling)"
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [clojure.java.io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-image "alpine:latest")
(def default-workdir "/workspace")
(def default-stop-timeout 5)

;; Default resource limits
(def default-resources
  {:memory "512m"
   :cpu 0.5})

;; Resource paths for Dockerfiles (for documentation/building images)
(def dockerfile-resources
  {:minimal "executor/docker/Dockerfile.task-runner"
   :clojure "executor/docker/Dockerfile.task-runner-clojure"})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- docker-cmd
  "Build docker command with optional custom path."
  [docker-path & args]
  (apply vector (or docker-path "docker") args))

(defn- run-docker
  "Execute a docker command and return the result."
  [docker-path & args]
  (try
    (apply shell/sh (apply docker-cmd docker-path args))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn- build-env-args
  "Build -e arguments for environment variables."
  [env-map]
  (mapcat (fn [[k v]] ["-e" (str (name k) "=" v)]) env-map))

(defn- build-resource-args
  "Build resource limit arguments.
   Merges provided resources with defaults."
  [resources]
  (let [merged (merge default-resources resources)]
    (cond-> []
      (:memory merged)
      (into ["--memory" (:memory merged)])

      (:cpu merged)
      (into ["--cpus" (str (:cpu merged))]))))

;; ============================================================================
;; Docker Operations
;; ============================================================================

(defn docker-info
  "Get Docker server version."
  [docker-path]
  (let [result (run-docker docker-path "info" "--format" "{{.ServerVersion}}")]
    (if (zero? (:exit result))
      {:available? true
       :docker-version (str/trim (:out result))}
      {:available? false
       :reason (:err result)})))

(defn create-container
  "Create and start a Docker container.

   Returns {:container-id string :container-name string} on success."
  [docker-path container-name image workdir env-map resources network]
  (let [env-args (build-env-args env-map)
        resource-args (build-resource-args resources)
        cmd-args (concat ["run" "-d"
                          "--name" container-name
                          "-w" workdir]
                         (when network ["--network" network])
                         env-args
                         resource-args
                         [image "sleep" "infinity"])
        result (apply run-docker docker-path cmd-args)]
    (if (zero? (:exit result))
      (result/ok {:container-id (str/trim (:out result))
                  :container-name container-name})
      (result/err :container-create-failed (:err result)))))

(defn exec-in-container
  "Execute a command in a running container."
  [docker-path container-id command opts]
  (let [cmd-args (if (string? command)
                   ["sh" "-c" command]
                   command)
        workdir-args (when (:workdir opts) ["-w" (:workdir opts)])
        env-args (build-env-args (:env opts))
        full-args (concat ["exec"]
                          workdir-args
                          env-args
                          [container-id]
                          cmd-args)
        start-time (System/currentTimeMillis)
        result (apply run-docker docker-path full-args)]
    (result/ok {:exit-code (:exit result)
                :stdout (:out result)
                :stderr (:err result)
                :duration-ms (- (System/currentTimeMillis) start-time)})))

(defn copy-to-container
  "Copy files from host to container."
  [docker-path container-id local-path remote-path]
  (let [result (run-docker docker-path "cp" local-path
                           (str container-id ":" remote-path))]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes -1}) ; docker cp doesn't report bytes
      (result/err :copy-failed (:err result)))))

(defn copy-from-container
  "Copy files from container to host."
  [docker-path container-id remote-path local-path]
  (let [result (run-docker docker-path "cp"
                           (str container-id ":" remote-path)
                           local-path)]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes -1})
      (result/err :copy-failed (:err result)))))

(defn stop-container
  "Stop a running container."
  [docker-path container-id timeout]
  (run-docker docker-path "stop" "-t" (str timeout) container-id))

(defn remove-container
  "Remove a container (force)."
  [docker-path container-id]
  (let [result (run-docker docker-path "rm" "-f" container-id)]
    (result/ok {:released? (zero? (:exit result))})))

(defn inspect-container
  "Get container status."
  [docker-path container-id]
  (let [result (run-docker docker-path "inspect" container-id
                           "--format" "{{.State.Status}}")]
    (if (zero? (:exit result))
      (result/ok {:status (case (str/trim (:out result))
                            "running" :running
                            "exited" :stopped
                            "dead" :error
                            :unknown)})
      (result/ok {:status :unknown :error (:err result)}))))

;; ============================================================================
;; Image Management
;; ============================================================================

(def task-runner-images
  "Pre-defined task runner images that can be built from bundled Dockerfiles."
  {:minimal {:image "miniforge/task-runner:latest"
             :dockerfile "executor/docker/Dockerfile.task-runner"
             :description "Minimal Alpine image with git, bash, curl, jq"}
   :clojure {:image "miniforge/task-runner-clojure:latest"
             :dockerfile "executor/docker/Dockerfile.task-runner-clojure"
             :description "Full Clojure image with clj, bb, clj-kondo, node"}})

(defn image-exists?
  "Check if a Docker image exists locally."
  [docker-path image]
  (let [result (run-docker docker-path "image" "inspect" image)]
    (zero? (:exit result))))

(defn- find-dockerfile-path
  "Find the Dockerfile resource on the classpath or filesystem.
   Returns absolute path to the Dockerfile."
  [resource-path]
  (let [resource (clojure.java.io/resource resource-path)]
    (cond
      ;; Direct file on classpath
      (and resource (= "file" (.getScheme (.toURI resource))))
      (.getAbsolutePath (clojure.java.io/file (.toURI resource)))

      ;; Resource in JAR — extract to temp file
      resource
      (let [temp-file (java.io.File/createTempFile "Dockerfile" ".tmp")]
        (.deleteOnExit temp-file)
        (with-open [in (clojure.java.io/input-stream resource)]
          (clojure.java.io/copy in temp-file))
        (.getAbsolutePath temp-file))

      ;; Try as direct filesystem path
      :else
      (let [f (clojure.java.io/file resource-path)]
        (when (.exists f)
          (.getAbsolutePath f))))))

(defn build-image!
  "Build a Docker image from a Dockerfile.

   Arguments:
   - docker-path: Path to docker binary (nil for default)
   - image-name: Name:tag for the image
   - dockerfile-path: Resource path to Dockerfile

   Returns result monad with build info or error."
  [docker-path image-name dockerfile-path]
  (if-let [abs-path (find-dockerfile-path dockerfile-path)]
    (let [context-dir (.getParent (clojure.java.io/file abs-path))
          _dockerfile-name (.getName (clojure.java.io/file abs-path))
          result (run-docker docker-path "build"
                             "-t" image-name
                             "-f" abs-path
                             context-dir)]
      (if (zero? (:exit result))
        (result/ok {:image image-name
                    :dockerfile dockerfile-path
                    :built? true})
        (result/err :build-failed {:image image-name
                                   :dockerfile dockerfile-path
                                   :error (:err result)})))
    (result/err :dockerfile-not-found {:path dockerfile-path})))

(defn ensure-image!
  "Ensure a Docker image exists, building if necessary.

   Arguments:
   - docker-path: Path to docker binary
   - image-key: Key from task-runner-images (:minimal or :clojure)
   - opts: {:force? bool} - force rebuild even if exists

   Returns result monad with image info."
  [docker-path image-key & {:keys [force?] :or {force? false}}]
  (if-let [image-spec (get task-runner-images image-key)]
    (let [{:keys [image dockerfile]} image-spec]
      (if (and (not force?) (image-exists? docker-path image))
        (result/ok {:image image
                    :dockerfile dockerfile
                    :built? false
                    :existed? true})
        (build-image! docker-path image dockerfile)))
    (result/err :unknown-image-key {:key image-key
                                    :available (keys task-runner-images)})))

(defn ensure-all-images!
  "Ensure all task runner images are available.

   Arguments:
   - docker-path: Path to docker binary
   - opts: {:force? bool} - force rebuild all

   Returns map of image-key -> result."
  [docker-path & {:keys [force?] :or {force? false}}]
  (into {}
        (for [image-key (keys task-runner-images)]
          [image-key (ensure-image! docker-path image-key :force? force?)])))

;; ============================================================================
;; DockerExecutor Record
;; ============================================================================

(defrecord DockerExecutor [config image network docker-path]
  proto/TaskExecutor

  (executor-type [_this] :docker)

  (available? [_this]
    (try
      (result/ok (docker-info docker-path))
      (catch Exception e
        (result/ok {:available? false :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    (let [container-name (str "miniforge-task-" (subs (str task-id) 0 8))
          workdir (or (:workdir env-config) default-workdir)
          create-result (create-container docker-path
                                          container-name
                                          image
                                          workdir
                                          (:env env-config)
                                          (:resources env-config)
                                          network)]
      (if (result/ok? create-result)
        (result/ok (proto/create-environment-record
                    container-name :docker task-id workdir
                    (assoc env-config :metadata (:data create-result))))
        create-result)))

  (execute! [_this environment-id command opts]
    (try
      (exec-in-container docker-path environment-id command opts)
      (catch Exception e
        (result/err :exec-failed (.getMessage e)))))

  (copy-to! [_this environment-id local-path remote-path]
    (try
      (copy-to-container docker-path environment-id local-path remote-path)
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (copy-from! [_this environment-id remote-path local-path]
    (try
      (copy-from-container docker-path environment-id remote-path local-path)
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (release-environment! [_this environment-id]
    (try
      (stop-container docker-path environment-id default-stop-timeout)
      (remove-container docker-path environment-id)
      (catch Exception e
        (result/err :release-failed (.getMessage e)))))

  (environment-status [_this environment-id]
    (try
      (inspect-container docker-path environment-id)
      (catch Exception e
        (result/ok {:status :unknown :error (.getMessage e)})))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-docker-executor
  "Create a Docker executor.

   Config:
   - :image - Container image for tasks (default: alpine:latest)
   - :network - Docker network to attach to
   - :docker-path - Path to docker binary"
  [config]
  (map->DockerExecutor
   {:config config
    :image (or (:image config) default-image)
    :network (:network config)
    :docker-path (:docker-path config)}))
