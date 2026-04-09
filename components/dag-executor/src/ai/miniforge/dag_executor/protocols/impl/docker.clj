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

(defn docker-cmd
  "Build docker command with optional custom path."
  [docker-path & args]
  (apply vector (or docker-path "docker") args))

(defn run-docker
  "Execute a docker command and return the result."
  [docker-path & args]
  (try
    (apply shell/sh (apply docker-cmd docker-path args))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn run-docker-process
  "Execute a docker command with optional stdin bytes."
  [docker-path args & {:keys [stdin-bytes]}]
  (try
    (let [pb (ProcessBuilder. (into-array String (apply docker-cmd docker-path args)))
          process (.start pb)]
      (when stdin-bytes
        (with-open [stdin (.getOutputStream process)]
          (.write stdin ^bytes stdin-bytes)))
      (let [stdout-bytes (.readAllBytes (.getInputStream process))
            stderr-bytes (.readAllBytes (.getErrorStream process))
            exit-code (.waitFor process)]
        {:exit exit-code
         :out-bytes stdout-bytes
         :err-bytes stderr-bytes
         :out (String. ^bytes stdout-bytes "UTF-8")
         :err (String. ^bytes stderr-bytes "UTF-8")}))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out "" :out-bytes (byte-array 0) :err-bytes (byte-array 0)})))

(defn shell-quote
  "Single-quote a shell argument."
  [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn build-env-args
  "Build -e arguments for environment variables."
  [env-map]
  (mapcat (fn [[k v]] ["-e" (str (name k) "=" v)]) env-map))

(def secret-pattern
  "Regex matching env var names that carry sensitive values."
  #"(?i)(KEY|SECRET|TOKEN|PASSWORD|CREDENTIAL)")

(defn filter-env-by-trust
  "Return env-map with sensitive entries removed for :untrusted trust level.

   For :trusted and :privileged trust levels the map is returned unchanged.
   A var is considered sensitive when its name contains any of:
   KEY, SECRET, TOKEN, PASSWORD, CREDENTIAL (case-insensitive)."
  [env-map trust-level]
  (if (= trust-level :untrusted)
    (into {} (remove (fn [[k _]] (re-find secret-pattern (name k))) env-map))
    env-map))

(defn build-mount-args
  "Build -v mount arguments from an execution plan map.

   Trust-level semantics:
   - :untrusted  — all mounts are forced read-only regardless of :read-only?
   - :trusted    — mounts respect the :read-only? flag on each entry
   - :privileged — same as :trusted (additional host paths are already listed
                   in the plan's :mounts vector by the caller)

   Each mount entry must satisfy ai.miniforge.dag-executor.execution-plan/MountSchema:
   {:host-path string :container-path string :read-only? boolean}"
  [execution-plan]
  (let [trust-level (get execution-plan :trust-level :untrusted)
        mounts      (get execution-plan :mounts [])]
    (mapcat (fn [{:keys [host-path container-path read-only?]}]
              (let [ro? (or (= trust-level :untrusted) read-only?)
                    mount-str (str host-path ":" container-path
                                   (when ro? ":ro"))]
                ["-v" mount-str]))
            mounts)))

(defn build-resource-args
  "Build resource limit arguments.
   Merges provided resources with defaults."
  [resources]
  (let [merged (merge default-resources resources)]
    (cond-> []
      (:memory merged)
      (into ["--memory" (:memory merged)])

      (:cpu merged)
      (into ["--cpus" (str (:cpu merged))]))))

(defn build-security-args
  "Build security-hardening docker args from an optional execution plan map.

   Applies a fixed set of hardening flags to every container plus
   network restrictions and memory limits derived from the plan:

   - :memory-limit-mb  RSS ceiling in mebibytes  (overrides default-resources)
   - :time-limit-ms    Not enforced at docker level (handled by executor logic)
   - :network-profile  :none/:restricted -> --network=none
                       :standard/:full   -> no extra network arg
   - :trust-level      Not used here; enforced at scheduling layer

   Always added:
   - --security-opt=no-new-privileges
   - --cap-drop=ALL
   - --read-only
   - --user 1000:1000"
  [execution-plan]
  (let [{:keys [memory-limit-mb network-profile]} execution-plan
        base ["--security-opt=no-new-privileges"
              "--cap-drop=ALL"
              "--read-only"
              "--user" "1000:1000"]]
    (cond-> base
      (some? memory-limit-mb)
      (into ["--memory" (str memory-limit-mb "m")])

      (#{:none :restricted} network-profile)
      (into ["--network=none"]))))

(defn build-runtime-fs-args
  "Build writable tmpfs mounts needed for a read-only container rootfs.

   Containers always run with `--read-only`, so common scratch paths must be
   provided explicitly. We keep `/tmp` writable and also provide a writable
   workdir scratch mount when the workdir is not already covered by caller
   mounts."
  [workdir execution-plan]
  (let [mounted-paths (into #{}
                            (map :container-path)
                            (get execution-plan :mounts []))
        scratch-paths (cond-> ["/tmp"]
                        (and workdir
                             (not (contains? mounted-paths workdir))
                             (not= workdir "/tmp"))
                        (conj workdir))]
    (mapcat (fn [path]
              ["--tmpfs" (str path ":rw,nosuid,nodev,exec,size=512m,uid=1000,gid=1000")])
            scratch-paths)))

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

   execution-plan (optional) is a map conforming to
   ai.miniforge.dag-executor.execution-plan/ExecutionPlanSchema.  When
   provided its :memory-limit-mb and :network-profile values take
   precedence over the legacy `network` argument for those concerns.
   All containers receive the standard security-hardening flags via
   `build-security-args`.

   Returns {:container-id string :container-name string} on success."
  [docker-path container-name image workdir env-map resources network
   & {:keys [execution-plan]}]
  (let [env-args      (build-env-args env-map)
        resource-args (build-resource-args resources)
        security-args (build-security-args execution-plan)
        runtime-fs-args (build-runtime-fs-args workdir execution-plan)
        mount-args    (when (some? execution-plan)
                        (build-mount-args execution-plan))
        ;; When an execution plan is present its network-profile drives the
        ;; network arg; fall back to the plain `network` string otherwise.
        network-args  (if (some? execution-plan)
                        [] ; network handled inside build-security-args
                        (when network ["--network" network]))
        cmd-args      (concat ["run" "-d"
                               "--name" container-name
                               "-w" workdir]
                              network-args
                              security-args
                              runtime-fs-args
                              mount-args
                              env-args
                              resource-args
                              [image "sleep" "infinity"])
        result        (apply run-docker docker-path cmd-args)]
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
  (let [source (clojure.java.io/file local-path)
        parent (.getParent (clojure.java.io/file remote-path))
        mkdir-cmd (when (seq parent)
                    (str "mkdir -p " (shell-quote parent) " && "))
        write-cmd (str (or mkdir-cmd "")
                       "cat > " (shell-quote remote-path))
        result (run-docker-process docker-path
                                   ["exec" "-i" container-id "sh" "-c" write-cmd]
                                   :stdin-bytes (java.nio.file.Files/readAllBytes (.toPath source)))]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes (.length source)})
      (result/err :copy-failed (:err result)))))

(defn copy-from-container
  "Copy files from container to host."
  [docker-path container-id remote-path local-path]
  (let [result (run-docker-process docker-path
                                   ["exec" container-id "sh" "-c"
                                    (str "cat " (shell-quote remote-path))])
        dest (clojure.java.io/file local-path)]
    (if (zero? (:exit result))
      (do
        (when-let [parent (.getParentFile dest)]
          (.mkdirs parent))
        (with-open [out (clojure.java.io/output-stream dest)]
          (.write out ^bytes (:out-bytes result)))
        (result/ok {:copied-bytes (alength ^bytes (:out-bytes result))}))
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

(defn find-dockerfile-path
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
;; Container command execution helper
;; ============================================================================

(defn- sanitize-token
  "Remove token credentials from a string for safe logging.
   Handles both GitHub (x-access-token) and GitLab (oauth2) auth formats."
  [s]
  (-> s
      (str/replace #"x-access-token:[^\s@]+" "x-access-token:***")
      (str/replace #"oauth2:[^\s@]+" "oauth2:***")))

(defn- container-exec-fn
  "Build a function that executes commands inside a container.
   Returns (fn [cmd] -> result-map).

   Options:
   - :throw-on-error? — throw ex-info on non-zero exit (default false)
   - :error-prefix — prefix for thrown error messages
   - :sanitize? — sanitize tokens in error messages (default false)"
  [docker-path container-id workdir
   & {:keys [throw-on-error? error-prefix sanitize?]
      :or {throw-on-error? false error-prefix "Command failed" sanitize? false}}]
  (fn [cmd]
    (let [r (exec-in-container docker-path container-id cmd {:workdir workdir})]
      (when (and throw-on-error?
                 (not (zero? (get-in r [:data :exit-code] 1))))
        (let [safe-cmd (if sanitize? (sanitize-token (str cmd)) (str cmd))
              stderr   (if sanitize?
                         (sanitize-token (or (get-in r [:data :stderr]) ""))
                         (or (get-in r [:data :stderr]) ""))]
          (throw (ex-info (str error-prefix ": " safe-cmd
                               (when (seq stderr) (str "\n" stderr)))
                          {:cmd safe-cmd :stderr stderr
                           :exit (get-in r [:data :exit-code])}))))
      r)))

;; ============================================================================
;; Workspace Bootstrap (N11 §4.2)
;; ============================================================================

(defn- infer-host-kind
  "Infer the git hosting provider from a repository URL.
   Returns :github, :gitlab, or :generic."
  [repo-url]
  (cond
    (str/includes? repo-url "github.com")  :github
    (str/includes? repo-url "gitlab.com")  :gitlab
    (str/includes? repo-url "gitlab")      :gitlab
    :else                                  :generic))

(defn- resolve-git-token
  "Resolve a git authentication token for capsule clone.
   One canonical env var per provider:
   - MINIFORGE_GIT_TOKEN — universal override (any host)
   - GITLAB_TOKEN — GitLab hosts
   - GH_TOKEN — GitHub hosts (fallback: `gh auth token` CLI)"
  [_repo-url {:keys [host-kind]}]
  (or (System/getenv "MINIFORGE_GIT_TOKEN")
      (case host-kind
        :gitlab (System/getenv "GITLAB_TOKEN")
        (or (System/getenv "GH_TOKEN")
            (try (let [r (clojure.java.shell/sh "gh" "auth" "token")]
                   (when (zero? (:exit r))
                     (str/trim (:out r))))
                 (catch Exception _ nil))))))


(defn- authenticated-https-url
  "Inject token credentials into an HTTPS git URL.
   Uses the host-appropriate token format based on host-kind."
  [https-url token host-kind]
  (str/replace https-url #"https://"
               (if (= host-kind :gitlab)
                 (str "https://oauth2:" token "@")
                 (str "https://x-access-token:" token "@"))))

(defn- bootstrap-workspace!
  "Clone a git repository into the container workspace after creation.
   Runs git clone, configures git identity, and checks out the target branch.
   No-op when repo-url is nil (local mode / pre-existing workspace).
   Uses config-based token resolution (profile + env fallback) rather than
   URL-guessing. Converts SSH URLs to HTTPS for containers where SSH agents
   are not available."
  [docker-path container-name workdir env-config]
  (when-let [repo-url (:repo-url env-config)]
    (let [branch    (or (:branch env-config) "main")
          host-kind (or (:host-kind env-config)
                        (infer-host-kind repo-url))
          token     (resolve-git-token repo-url {:host-kind host-kind})
          ;; Convert SSH URL to HTTPS (containers lack SSH agent)
          https-url (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" repo-url)]
                      (str "https://" host "/" path)
                      repo-url)
          clone-url (if token
                      (authenticated-https-url https-url token host-kind)
                      repo-url)
          exec!  (container-exec-fn docker-path container-name "/"
                                    :throw-on-error? true
                                    :error-prefix "Workspace bootstrap failed"
                                    :sanitize? true)
          clone-cmd (str "git clone --branch " branch " --single-branch "
                         clone-url " " workdir)]
      (exec! clone-cmd)
      ;; Use --local (not --global) since container rootfs is read-only
      (exec! (str "git -C " workdir " config user.email 'miniforge@miniforge.ai'"))
      (exec! (str "git -C " workdir " config user.name 'miniforge'"))
      ;; Set push URL with token so persist-workspace! can push
      (when token
        (exec! (str "git -C " workdir " remote set-url --push origin "
                    (authenticated-https-url https-url token host-kind)))))))

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
    ;; Lazy image build: ensure the image exists locally before creating the container.
    ;; Looks up image in task-runner-images by name; builds from bundled Dockerfile if missing.
    (when-not (image-exists? docker-path image)
      (when-let [image-key (->> task-runner-images
                                (some (fn [[k v]] (when (= image (:image v)) k))))]
        (ensure-image! docker-path image-key)))
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
        (do
          ;; Bootstrap workspace: clone repo into container if repo-url provided (N11 §4.2)
          (bootstrap-workspace! docker-path container-name workdir env-config)
          (result/ok (proto/create-environment-record
                      container-name :docker task-id workdir
                      (assoc env-config :metadata (:data create-result)))))
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
        (result/ok {:status :unknown :error (.getMessage e)}))))

  (persist-workspace! [_this environment-id opts]
    (let [workdir (or (:workdir opts) default-workdir)
          branch  (or (:branch opts) "task/unknown")
          message (or (:message opts) "phase checkpoint")]
      (try
        (let [exec! (container-exec-fn docker-path environment-id workdir)
              ;; Stage all changes
              _ (exec! "git add -A")
              ;; Check if there are changes to commit
              status-r (exec! "git status --porcelain")
              has-changes? (seq (str/trim (get-in status-r [:data :stdout] "")))]
          (if has-changes?
            (let [_ (exec! (str "git commit -m '" message "'"))
                  ;; Push to task branch (create if needed)
                  push-r (exec! (str "git push origin HEAD:" branch " --force"))
                  ;; Get commit SHA
                  sha-r  (exec! "git rev-parse HEAD")
                  sha    (str/trim (get-in sha-r [:data :stdout] ""))]
              (result/ok {:persisted? true :commit-sha sha :branch branch}))
            (result/ok {:persisted? false :commit-sha nil :no-changes? true})))
        (catch Exception e
          (result/err :persist-failed (.getMessage e))))))

  (restore-workspace! [_this environment-id opts]
    (let [workdir (or (:workdir opts) default-workdir)
          branch  (or (:branch opts) "task/unknown")]
      (try
        (let [exec! (container-exec-fn docker-path environment-id workdir)
              _ (exec! (str "git fetch origin " branch))
              _ (exec! (str "git checkout " branch))
              sha-r (exec! "git rev-parse HEAD")
              sha   (str/trim (get-in sha-r [:data :stdout] ""))]
          (result/ok {:restored? true :commit-sha sha :branch branch}))
        (catch Exception e
          (result/err :restore-failed (.getMessage e)))))))

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
