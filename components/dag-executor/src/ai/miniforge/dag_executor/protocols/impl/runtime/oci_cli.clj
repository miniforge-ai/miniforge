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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli
  "OCI-CLI executor — generalized container executor parameterized by a
   runtime descriptor.

   Per-kind data (executable, capability set, container defaults, CLI flag
   dialect) lives in `runtime/registry.edn`. Default OCI image references
   live in `runtime/images.edn` (fully qualified per N11-delta §5).

   Currently supported runtime kinds: `:docker` (Phase 1) and `:podman`
   (Phase 2). `:nerdctl` is known but not supported; constructing a
   descriptor for it raises `:runtime/unsupported`.

   Adding a runtime is an EDN edit — flip the kind's `:supported?` to true,
   declare its `:capabilities`, `:defaults`, and any `:flags` overrides,
   and the executor picks it up without code changes."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.dag-executor.plan-security :as plan-security]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.workspace :as workspace]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.dag-executor.protocols.impl.runtime.images :as images]
   [ai.miniforge.dag-executor.protocols.impl.runtime.process :as runtime-process]
   [ai.miniforge.dag-executor.protocols.impl.runtime.registry :as registry]
   [clojure.java.io]
   [clojure.string :as str]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-workdir "/workspace")

(def default-acquisition-timeout-ms
  "Default ceiling on `acquire-environment!` wall-clock duration. Picked
   so a cold-cache image pull (~60 s on a slow link for the runner
   images) still fits, while a stuck Docker daemon fails within two
   minutes instead of blocking the workflow indefinitely. Override per
   call via `env-config[:acquisition-timeout-ms]`. The 2026-05-16
   dogfood hung 33+ minutes on stuck Docker acquires before PR #895's
   test-side mock landed; the production-side guard lives here."
  120000)

(defn- acquire-failed-result
  [throwable]
  (result/err :acquire-failed
              (or (ex-message throwable) (messages/t :oci/acquire-failed))
              (cond-> {:surface :acquire-environment!
                       :exception-class (.getName (class throwable))}
                (ex-data throwable) (assoc :exception-data (ex-data throwable)))))

(defn- call-acquire-body
  [body-fn]
  (try
    (body-fn)
    (catch Throwable t
      (acquire-failed-result t))))

(defn with-acquisition-timeout
  "Run `body-fn` (zero-arity) with a wall-clock deadline. Returns the
   body's return value when it completes in time, or a `:timeout`
   error result (the standard dag-executor code) when the deadline
   fires.

   `timeout-ms` of `nil` or `<= 0` disables the timeout (tests that
   pre-mock the executor wholesale rely on this escape hatch).

   Caveats and follow-ups:

   - Exceptions thrown by `body-fn` are returned as a standard
     `:acquire-failed` error result. `deref` would otherwise wrap them
     in `ExecutionException`; this fn captures the original throwable so
     callers receive explicit failure data instead of exception control
     flow.
   - `future-cancel` only interrupts the worker thread; it does NOT
     interrupt owned child processes now that runtime calls use
     ProcessBuilder directly instead of `clojure.java.shell/sh`."
  [timeout-ms body-fn]
  (if (or (nil? timeout-ms) (not (pos? timeout-ms)))
    (call-acquire-body body-fn)
    (let [fut (future
                {::ok (call-acquire-body body-fn)})
          deref-result (deref fut timeout-ms ::timeout)]
      (cond
        (= ::timeout deref-result)
        (do
          (future-cancel fut)
          (binding [*out* *err*]
            (println (messages/t :oci/acquire-deadline {:ms timeout-ms})))
          (result/err :timeout
                      (messages/t :oci/acquire-timeout {:ms timeout-ms})
                      {:timeout-ms timeout-ms
                       :surface :acquire-environment!}))

        :else
        (::ok deref-result)))))

(def default-stop-timeout
  "Minimum graceful-stop timeout (seconds). Also the floor for the timeout
   computed from an execution plan's :time-limit-ms — `--stop-timeout`
   never goes below this."
  5)

(def default-exec-timeout-ms
  "Ceiling on a single `docker exec` invocation (`execute!`). Five minutes
   covers long-running agent commands while still bounding a hung pty or
   container-namespace teardown. Companion follow-up to the 2026-05-16
   dogfood fix: `acquire-environment!` was bounded by PR #895;
   `execute!`, `release-environment!`, `copy-to!`, and `copy-from!`
   are bounded here."
  300000)

(def default-copy-timeout-ms
  "Ceiling on a `docker exec`-based file-copy operation (`copy-to!` /
   `copy-from!`). Two minutes accommodates large workspace transfers
   while preventing an indefinite hang if Docker stalls mid-pipe."
  120000)

(def default-release-timeout-ms
  "Ceiling on container stop + remove during `release-environment!`.
   Two minutes bounds the stop/rm sequence that can hang on pty orphans
   or container-namespace teardown."
  120000)

(def container-name-prefix
  "Prefix for generated container names: <prefix><task-id-slice>."
  "miniforge-task-")

(def container-name-uuid-slice
  "Number of characters to take from the task UUID when constructing the
   container name. Eight is enough collision space for a single-host
   workflow."
  8)

;; Resource paths for Dockerfiles (for documentation/building images).
;; The Dockerfile syntax is OCI-standard; "docker/" in the resource path is
;; historical and stays unchanged in Phase 1.
(def dockerfile-resources
  {:minimal "executor/docker/Dockerfile.task-runner"
   :clojure "executor/docker/Dockerfile.task-runner-clojure"})

(defn- runtime-default-resources
  "Per-runtime memory/cpu defaults sourced from the registry. Wrapping the
   two lookups in a tiny constructor keeps the resource-arg builder a flat
   key-value map."
  [descriptor]
  (let [k (descriptor/kind descriptor)]
    {:memory (registry/default k :memory)
     :cpu    (registry/default k :cpu)}))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn runtime-cmd
  "Build a runtime CLI invocation vector for the given descriptor."
  [descriptor & args]
  (apply vector (descriptor/executable descriptor) args))

(defn- run-runtime-timed
  "Execute a runtime CLI command with an optional per-subprocess waitFor
   deadline.

   When `timeout-ms` is nil or <= 0 the call is unbounded (identical to the
   pre-fix behaviour). On timeout the process tree is destroyed forcibly and
   {:exit 124 :out \"\" :err <message>} is returned; callers that check
   `(zero? :exit)` surface this as a command failure without special-casing."
  [timeout-ms descriptor & args]
  (try
    (let [pb (ProcessBuilder. (into-array String (apply runtime-cmd descriptor args)))
          process (.start pb)
          stdout-fut (runtime-process/read-stream-future (.getInputStream process))
          stderr-fut (runtime-process/read-stream-future (.getErrorStream process))]
      (try
        (let [timed?     (and timeout-ms (pos? timeout-ms))
              completed? (if timed?
                           (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                           (do (.waitFor process) true))]
          (if-not completed?
            (do
              (runtime-process/destroy-process-tree! process)
              (future-cancel stdout-fut)
              (future-cancel stderr-fut)
              (binding [*out* *err*]
                (println (messages/t :oci/command-timeout-log {:ms timeout-ms})))
              {:exit 124
               :out  ""
               :err  (messages/t :oci/command-timeout {:ms timeout-ms})})
            (let [stdout-bytes @stdout-fut
                  stderr-bytes @stderr-fut]
              {:exit (.exitValue process)
               :out  (String. ^bytes stdout-bytes "UTF-8")
               :err  (String. ^bytes stderr-bytes "UTF-8")})))
        (catch InterruptedException e
          (runtime-process/destroy-process-tree! process)
          (future-cancel stdout-fut)
          (future-cancel stderr-fut)
          (.interrupt (Thread/currentThread))
          {:exit 130 :err (or (ex-message e) (messages/t :oci/command-interrupted)) :out ""})))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn run-runtime
  "Execute a runtime CLI command and return the result.
   Internal callers that need a bounded deadline use `run-runtime-timed`
   directly; this public entry point preserves the unbounded signature
   for backward-compatibility with call sites already covered by an outer
   `with-acquisition-timeout` guard."
  [descriptor & args]
  (apply run-runtime-timed nil descriptor args))

(defn- run-runtime-process-timed
  "Execute a runtime CLI command with optional stdin bytes and an optional
   per-subprocess waitFor deadline.

   When `timeout-ms` is nil or <= 0 the call is unbounded. On timeout the
   process tree is destroyed forcibly and a result with :exit 124 is returned
   so callers that check `(zero? :exit)` surface it as a command failure."
  [timeout-ms descriptor args & {:keys [stdin-bytes]}]
  (try
    (let [pb (ProcessBuilder. (into-array String (apply runtime-cmd descriptor args)))
          process (.start pb)
          stdout-fut (runtime-process/read-stream-future (.getInputStream process))
          stderr-fut (runtime-process/read-stream-future (.getErrorStream process))
          stdin-fut  (when stdin-bytes
                       (let [s (.getOutputStream process)]
                         (future
                           (try
                             (.write s ^bytes stdin-bytes)
                             (finally (.close s))))))]
      (try
        (let [timed?     (and timeout-ms (pos? timeout-ms))
              completed? (if timed?
                           (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                           (do (.waitFor process) true))]
          (if-not completed?
            (do
              (when stdin-fut (future-cancel stdin-fut))
              (runtime-process/destroy-process-tree! process)
              (future-cancel stdout-fut)
              (future-cancel stderr-fut)
              (binding [*out* *err*]
                (println (messages/t :oci/command-timeout-log {:ms timeout-ms})))
              {:exit      124
               :out       ""
               :out-bytes (byte-array 0)
               :err       (messages/t :oci/command-timeout {:ms timeout-ms})
               :err-bytes (byte-array 0)})
            (let [stdout-bytes @stdout-fut
                  stderr-bytes @stderr-fut]
              {:exit      (.exitValue process)
               :out-bytes stdout-bytes
               :err-bytes stderr-bytes
               :out       (String. ^bytes stdout-bytes "UTF-8")
               :err       (String. ^bytes stderr-bytes "UTF-8")})))
        (catch InterruptedException e
          (when stdin-fut (future-cancel stdin-fut))
          (runtime-process/destroy-process-tree! process)
          (future-cancel stdout-fut)
          (future-cancel stderr-fut)
          (.interrupt (Thread/currentThread))
          {:exit      130
           :err       (or (ex-message e) (messages/t :oci/command-interrupted))
           :out       ""
           :out-bytes (byte-array 0)
           :err-bytes (byte-array 0)})))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out "" :out-bytes (byte-array 0) :err-bytes (byte-array 0)})))

(defn run-runtime-process
  "Execute a runtime CLI command with optional stdin bytes.
   Internal callers that need a bounded deadline use `run-runtime-process-timed`
   directly; this public entry point is unbounded for backward-compatibility."
  [descriptor args & {:keys [stdin-bytes]}]
  (run-runtime-process-timed nil descriptor args :stdin-bytes stdin-bytes))

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
   Merges provided resources with the runtime's per-kind defaults from the
   registry."
  ([descriptor resources]
   (build-resource-args descriptor resources {}))
  ([descriptor resources {:keys [omit-memory?]}]
   (let [merged (merge (runtime-default-resources descriptor) resources)]
     (cond-> []
       (and (not omit-memory?) (:memory merged))
       (into ["--memory" (:memory merged)])

       (:cpu merged)
       (into ["--cpus" (str (:cpu merged))])))))

(defn build-security-args
  "Build security-hardening runtime args from an optional execution plan map.

   Applies a fixed set of hardening flags to every container plus
   network restrictions and memory limits derived from the plan:

   - :memory-limit-mb  RSS ceiling in mebibytes  (overrides registry default)
   - :time-limit-ms    Not enforced at runtime level (handled by executor logic)
   - :network-profile  :none/:restricted -> --network=none
                       :standard/:full   -> no extra network arg
   - :trust-level      Not used here; enforced at scheduling layer

   Always added:
   - --security-opt=no-new-privileges
   - --cap-drop=ALL
   - --read-only
   - --user <uid>:<gid> from the runtime registry's :uid / :gid defaults"
  [descriptor execution-plan]
  (let [{:keys [memory-limit-mb network-profile]} execution-plan
        user-spec (registry/user-spec (descriptor/kind descriptor))
        base ["--security-opt=no-new-privileges"
              "--cap-drop=ALL"
              "--read-only"
              "--user" user-spec]]
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
   mounts.

   The tmpfs option string is built by `registry/tmpfs-mount-options` from
   the runtime's `:defaults` (`:uid`, `:gid`, `:tmpfs-size`) so a future
   runtime that diverges (e.g. does not accept `uid=`/`gid=` on tmpfs) is
   papered over by adjusting that helper or its registry inputs rather
   than branching on `:runtime/kind` here."
  [descriptor workdir execution-plan]
  (let [mounted-paths (into #{}
                            (map :container-path)
                            (get execution-plan :mounts []))
        scratch-paths (cond-> ["/tmp"]
                        (and workdir
                             (not (contains? mounted-paths workdir))
                             (not= workdir "/tmp"))
                        (conj workdir))
        opts          (registry/tmpfs-mount-options (descriptor/kind descriptor))]
    (mapcat (fn [path]
              ["--tmpfs" (str path ":" opts)])
            scratch-paths)))

;; ============================================================================
;; Container Operations
;; ============================================================================

(defn create-container
  "Create and start a container.

   When execution-plan is present, it is authoritative for plan-owned launch
   settings: security flags, runtime filesystems, mounts, networking, time
   limit, and command. Absent plan fields do not fall back to the legacy
   `network` argument; a partial plan therefore means no explicit network
   flag and uses the default keep-alive command when :command is absent.
   Production launch plans conform to
   ai.miniforge.dag-executor.execution-plan/ExecutionPlanSchema, while this
   compatibility boundary also accepts partial plan maps.

   Returns {:container-id string :container-name string} on success."
  [descriptor container-name image workdir env-map resources network
   & {:keys [execution-plan]}]
  (let [env-args      (build-env-args env-map)
        resource-args (build-resource-args
                       descriptor resources
                       {:omit-memory? (some? (:memory-limit-mb execution-plan))})
        security-args (build-security-args descriptor execution-plan)
        runtime-fs-args (build-runtime-fs-args descriptor workdir execution-plan)
        mount-args    (when (some? execution-plan)
                        (build-mount-args execution-plan))
        ;; An execution plan is authoritative for networking. The legacy
        ;; `network` argument applies only when no plan was supplied.
        network-args  (if (some? execution-plan)
                        [] ; network handled inside build-security-args
                        (when network ["--network" network]))
        ;; N11 §2.2: Set runtime stop-timeout from execution plan time limit
        stop-timeout  (when-let [ms (get-in execution-plan [:time-limit-ms])]
                        (max default-stop-timeout (quot ms 1000)))
        cmd-args      (concat ["run" "-d"
                               "--name" container-name
                               "-w" workdir]
                              (when stop-timeout
                                ["--stop-timeout" (str stop-timeout)])
                              network-args
                              security-args
                              runtime-fs-args
                              mount-args
                              env-args
                              resource-args
                              (into [image] (get execution-plan :command ["sleep" "infinity"])))
        result        (apply run-runtime descriptor cmd-args)]
    (if (zero? (:exit result))
      (result/ok {:container-id (str/trim (:out result))
                  :container-name container-name})
      (result/err :container-create-failed (:err result)))))

(defn exec-in-container
  "Execute a command in a running container."
  [descriptor container-id command opts]
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
        timeout-ms (if (contains? opts :timeout-ms)
                     (:timeout-ms opts)
                     default-exec-timeout-ms)
        start-time (System/currentTimeMillis)
        result (apply run-runtime-timed timeout-ms descriptor full-args)]
    (result/ok {:exit-code (:exit result)
                :stdout (:out result)
                :stderr (:err result)
                :duration-ms (- (System/currentTimeMillis) start-time)})))

(defn copy-to-container
  "Copy files from host to container."
  [descriptor container-id local-path remote-path]
  (let [source (clojure.java.io/file local-path)
        parent (.getParent (clojure.java.io/file remote-path))
        mkdir-cmd (when (seq parent)
                    (str "mkdir -p " (shell-quote parent) " && "))
        write-cmd (str (or mkdir-cmd "")
                       "cat > " (shell-quote remote-path))
        result (run-runtime-process-timed default-copy-timeout-ms
                                         descriptor
                                         ["exec" "-i" container-id "sh" "-c" write-cmd]
                                         :stdin-bytes (java.nio.file.Files/readAllBytes (.toPath source)))]
    (if (zero? (:exit result))
      (result/ok {:copied-bytes (.length source)})
      (result/err :copy-failed (:err result)))))

(defn copy-from-container
  "Copy files from container to host."
  [descriptor container-id remote-path local-path]
  (let [result (run-runtime-process-timed default-copy-timeout-ms
                                         descriptor
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
  [descriptor container-id timeout]
  (run-runtime-timed default-release-timeout-ms descriptor "stop" "-t" (str timeout) container-id))

(defn remove-container
  "Remove a container (force)."
  [descriptor container-id]
  (let [result (run-runtime-timed default-release-timeout-ms descriptor "rm" "-f" container-id)]
    (result/ok {:released? (zero? (:exit result))})))

(defn inspect-container
  "Get container status."
  [descriptor container-id]
  (let [result (run-runtime descriptor "inspect" container-id
                            "--format" "{{.State.Status}}")]
    (if (zero? (:exit result))
      (result/ok {:status (case (str/trim (:out result))
                            "running" :running
                            "exited" :stopped
                            "dead" :error
                            :unknown)})
      (result/ok {:status :unknown :error (:err result)}))))

(defn- normalize-image-id
  "Normalize a runtime-printed image ID to the `sha256:<64hex>` digest form.
   Docker prints IDs as `sha256:<hex>`; Podman prints the bare `<hex>`. The
   digest gate and the evidence record both expect the prefixed form."
  [id]
  (when-let [id (some-> id str/trim not-empty)]
    (let [id (str/lower-case id)]
      (if (re-matches #"^[a-f0-9]{64}$" id)
        (str "sha256:" id)
        id))))

(defn container-image-digest
  "Return the SHA256 image digest for a container, or nil on failure."
  [descriptor container-name]
  (try
    (let [inspect-result (run-runtime descriptor
                                      "inspect" container-name
                                      "--format" "{{.Image}}")]
      (when (zero? (:exit inspect-result))
        (normalize-image-id (:out inspect-result))))
    (catch Exception _ nil)))

(defn image-config-digest
  "Return the bare `sha256:<64hex>` content-addressed config digest for a local
   image, or nil on failure. Uses the image `.Id` (always present on any local
   image, including ones built locally that carry no registry RepoDigests), so
   the pinned digest can be resolved BEFORE the container is created — the value
   the capsule security gate checks (runtime-require-image-digest-pin)."
  [descriptor image]
  (try
    (let [result (run-runtime descriptor
                              "image" "inspect" image
                              "--format" "{{.Id}}")]
      (when (zero? (:exit result))
        (normalize-image-id (:out result))))
    (catch Exception _ nil)))

;; ============================================================================
;; Image Management
;; ============================================================================

(def task-runner-images
  "Pre-defined task runner images that can be built from bundled Dockerfiles.
   Sourced from `runtime/images.edn` so image references stay
   configuration-not-code. Resolved at namespace load and exposed as a map
   for callers that expect map semantics."
  (images/task-runner-images))

(defn image-exists?
  "Check if a container image exists locally."
  [descriptor image]
  (let [result (run-runtime descriptor "image" "inspect" image)]
    (zero? (:exit result))))

(defn image-repo-digest
  "Return the repo-digest (sha256:…) string for a local image,
   or nil when the image is not found or the runtime is unavailable."
  [descriptor image]
  (try
    (let [result (run-runtime descriptor
                              "image" "inspect" image
                              "--format" "{{index .RepoDigests 0}}")]
      (when (zero? (:exit result))
        (some-> (:out result) clojure.string/trim not-empty)))
    (catch Exception _e
      nil)))

(defn pull-image!
  "Pull `image` via the runtime. Best-effort: a failed pull (e.g. a
   locally-built image with no registry) leaves the image absent, which the
   caller surfaces as a nil digest."
  [descriptor image]
  (try (run-runtime descriptor "pull" image) (catch Exception _ nil)))

(defn resolve-image-digest!
  "Resolve the pinned `sha256:<64hex>` digest for `image`, pulling it first when
   it is not present locally so `image inspect` can read it (inspect does not
   pull). Returns nil only when the image cannot be resolved even after a pull —
   a genuinely unpinnable image, which the security gate then blocks. This is
   the production digest resolver injected into `security-gate-check`."
  [descriptor image]
  (when-not (image-exists? descriptor image)
    (pull-image! descriptor image))
  (image-config-digest descriptor image))

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
  "Build a container image from a Dockerfile.

   Arguments:
   - descriptor: Runtime descriptor (use the same exe to build that runs)
   - image-name: Name:tag for the image
   - dockerfile-path: Resource path to Dockerfile

   Returns result monad with build info or error."
  [descriptor image-name dockerfile-path]
  (if-let [abs-path (find-dockerfile-path dockerfile-path)]
    (let [context-dir (.getParent (clojure.java.io/file abs-path))
          _dockerfile-name (.getName (clojure.java.io/file abs-path))
          result (run-runtime descriptor "build"
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
  "Ensure a container image exists, building if necessary.

   Arguments:
   - descriptor: Runtime descriptor
   - image-key: Key from task-runner-images (:minimal or :clojure)
   - opts: {:force? bool} - force rebuild even if exists

   Returns result monad with image info."
  [descriptor image-key & {:keys [force?] :or {force? false}}]
  (if-let [image-spec (get task-runner-images image-key)]
    (let [{:keys [image dockerfile]} image-spec]
      (if (and (not force?) (image-exists? descriptor image))
        (result/ok {:image image
                    :dockerfile dockerfile
                    :built? false
                    :existed? true})
        (build-image! descriptor image dockerfile)))
    (result/err :unknown-image-key {:key image-key
                                    :available (keys task-runner-images)})))

(defn ensure-all-images!
  "Ensure all task runner images are available.

   Arguments:
   - descriptor: Runtime descriptor
   - opts: {:force? bool} - force rebuild all

   Returns map of image-key -> result."
  [descriptor & {:keys [force?] :or {force? false}}]
  (into {}
        (for [image-key (keys task-runner-images)]
          [image-key (ensure-image! descriptor image-key :force? force?)])))

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
   - :error-on-failure? — return result/err on non-zero exit (default false)
   - :error-prefix — prefix for result error messages
   - :sanitize? — sanitize tokens in error messages (default false)"
  [descriptor container-id workdir
   & {:keys [error-on-failure? error-prefix sanitize?]
      :or {error-on-failure? false error-prefix (messages/t :oci/command-failed) sanitize? false}}]
  (fn [cmd]
    (let [r (exec-in-container descriptor container-id cmd {:workdir workdir})]
      (if (and error-on-failure?
               (not (zero? (get-in r [:data :exit-code] 1))))
        (let [safe-cmd (if sanitize? (sanitize-token (str cmd)) (str cmd))
              stderr   (if sanitize?
                         (sanitize-token (or (get-in r [:data :stderr]) ""))
                         (or (get-in r [:data :stderr]) ""))]
          (result/err :container-command-failed
                      (messages/t :oci/command-failed-detail
                                  {:prefix error-prefix :command safe-cmd
                                   :stderr (if (seq stderr) (str "\n" stderr) "")})
                      {:cmd safe-cmd
                       :stderr stderr
                       :exit (get-in r [:data :exit-code])}))
        r))))

(defn- chain-container-command
  [result cmd-fn]
  (if (result/ok? result)
    (cmd-fn)
    result))

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
   Delegates to config/resolve-token which uses the profile + env chain:
   MINIFORGE_GIT_TOKEN → profile → provider env var → CLI fallback."
  [_repo-url {:keys [host-kind]}]
  (config/resolve-token host-kind))


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
   Returns a result map with bootstrap metadata. No-op success when
   repo-url is nil (local mode / pre-existing workspace).
   Uses config-based token resolution (profile + env fallback) rather than
   URL-guessing. Converts SSH URLs to HTTPS for containers where SSH agents
   are not available."
  [descriptor container-name workdir env-config]
  (if-let [repo-url (:repo-url env-config)]
    (let [branch    (get env-config :branch "main")
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
          exec!  (container-exec-fn descriptor container-name "/"
                                    :error-on-failure? true
                                    :error-prefix (messages/t :oci/bootstrap-failed)
                                    :sanitize? true)
          clone-cmd (str "git clone --branch " branch " --single-branch "
                         clone-url " " workdir)
          result (-> (exec! clone-cmd)
                     ;; Use --local (not --global) since container rootfs is read-only
                     (chain-container-command
                      #(exec! (str "git -C " workdir
                                   " config user.email 'miniforge@miniforge.ai'")))
                     (chain-container-command
                      #(exec! (str "git -C " workdir " config user.name 'miniforge'"))))]
      (if (result/err? result)
        result
        (let [push-url-result (if token
                                (exec! (str "git -C " workdir " remote set-url --push origin "
                                            (authenticated-https-url https-url token host-kind)))
                                result)
              sha-r (chain-container-command
                     push-url-result
                     #(exec! (str "git -C " workdir " rev-parse HEAD")))]
          (if (result/ok? sha-r)
            (let [base-sha (str/trim (get-in sha-r [:data :stdout] ""))]
              (result/ok (cond-> {:base-branch branch}
                           (seq base-sha) (assoc :base-sha base-sha))))
            sha-r))))
    (result/ok nil)))

;; ============================================================================
;; Capsule security gate — runtime enforcement of the runtime-* policies
;; ============================================================================

(defn build-launch-plan
  "Assemble the execution plan for a pending launch from what the launch site
   actually knows: the resolved image digest, the caller's mounts / env /
   trust level from env-config, and the capsule's keep-alive command. Optional
   fields (:time-limit-ms :memory-limit-mb :network-profile :secrets-refs)
   pass through only when env-config carries them — absent fields stay absent
   rather than being filled with guesses.

   This one plan is both what `check-plan-security` inspects and what
   `create-container` receives as :execution-plan, so the plan the gate
   checked is the plan that runs."
  [image-digest env-config]
  (cond-> {:image-digest image-digest
           :command      ["sleep" "infinity"]
           :mounts       (get env-config :mounts [])
           :env          (get env-config :env {})
           :trust-level  (get env-config :trust-level :untrusted)}
    (:time-limit-ms env-config)   (assoc :time-limit-ms (:time-limit-ms env-config))
    (:memory-limit-mb env-config) (assoc :memory-limit-mb (:memory-limit-mb env-config))
    (:network-profile env-config) (assoc :network-profile (:network-profile env-config))
    (:secrets-refs env-config)    (assoc :secrets-refs (:secrets-refs env-config))))

(defn security-gate-check
  "Run the capsule-isolation security gate for a pending launch. Resolves the
   image's content-addressed digest via `digest-fn` (injected for testing),
   builds the launch plan the gate inspects via `build-launch-plan`, and
   applies `plan-security/check-plan-security`. The host-path allowlist is the
   sandbox workdir (the only host path a well-formed plan mounts today).

   Returns `result/ok` carrying the gate decision when the launch may proceed
   (a passing gate may still carry non-blocking :security/warnings — e.g. a
   non-rootless runtime in OSS), or `result/err :security-policy-violation`
   with the findings on a hard-stop. A nil digest fails closed (hard-stop),
   since a plan with no pinned digest cannot satisfy require-image-digest-pin.

   A passing result also carries the checked `:launch-plan`;
   `acquire-environment!` creates the container FROM that plan — its digest as
   the immutable image reference, the plan itself as create-container's
   :execution-plan."
  [descriptor image env-config digest-fn]
  (let [plan            (build-launch-plan (digest-fn descriptor image) env-config)
        security-config {:host-path-allowlist #{(get env-config :workdir default-workdir)}
                         :rootless-action     plan-security/default-rootless-action}
        decision        (plan-security/check-plan-security plan descriptor security-config)]
    (if (response/anomaly-map? decision)
      (result/err :security-policy-violation
                  (:anomaly/message decision)
                  {:security/findings (:security/findings decision)})
      (result/ok (assoc (:output decision) :launch-plan plan)))))

;; ============================================================================
;; OciCliExecutor Record
;; ============================================================================

(defrecord OciCliExecutor [config descriptor image network]
  ;; Per-operation timeout guards (2026-07-18, follow-up to 2026-05-16 dogfood):
  ;; `acquire-environment!` is bounded by `with-acquisition-timeout` (PR #895).
  ;; `execute!`, `release-environment!`, `copy-to!`, and `copy-from!` each route
  ;; through `run-runtime-timed` / `run-runtime-process-timed` which use the
  ;; bounded `.waitFor(ms, TimeUnit/MILLISECONDS)` + `destroy-process-tree!` path,
  ;; so a stuck Docker daemon or hung pty causes a clean timeout error instead of
  ;; parking the JVM thread indefinitely.
  proto/TaskExecutor

  (executor-type [_this]
    (descriptor/kind descriptor))

  (available? [_this]
    (try
      (result/ok (descriptor/runtime-info descriptor))
      (catch Exception e
        (result/ok {:available? false :reason (.getMessage e)}))))

  (acquire-environment! [_this task-id env-config]
    ;; Wrap in with-acquisition-timeout so a stuck daemon or hung image
    ;; pull fails fast (default 120 s) instead of blocking the workflow
    ;; indefinitely. Per-call override via env-config :acquisition-timeout-ms.
    (with-acquisition-timeout
      (get env-config :acquisition-timeout-ms default-acquisition-timeout-ms)
      (fn []
        ;; Lazy image build: ensure the image exists locally before creating the container.
        ;; Looks up image in task-runner-images by name; builds from bundled Dockerfile if missing.
        (when-not (image-exists? descriptor image)
          (when-let [image-key (->> task-runner-images
                                    (some (fn [[k v]] (when (= image (:image v)) k))))]
            (ensure-image! descriptor image-key)))
        ;; Capsule security gate (runtime-* policies): resolve the pinned image
        ;; digest and check the plan BEFORE launch. A hard-stop (forbidden)
        ;; aborts the acquire without creating a container.
        (let [gate-result (security-gate-check descriptor image env-config resolve-image-digest!)]
          (if (result/err? gate-result)
            gate-result
            (let [container-name (str container-name-prefix
                                      (subs (str task-id) 0 container-name-uuid-slice))
                  workdir (get env-config :workdir default-workdir)
                  ;; Launch FROM the gate-checked plan: its digest as the
                  ;; immutable image reference, the plan itself as the
                  ;; execution plan — what runs is what was checked.
                  launch-plan (get-in gate-result [:data :launch-plan])
                  create-result (create-container descriptor
                                                  container-name
                                                  (:image-digest launch-plan)
                                                  workdir
                                                  (:env env-config)
                                                  (:resources env-config)
                                                  network
                                                  :execution-plan launch-plan)]
              (if (result/ok? create-result)
                ;; Bootstrap workspace: clone repo into container if repo-url provided (N11 §4.2)
                (let [bootstrap-result (bootstrap-workspace! descriptor container-name workdir env-config)]
                  (if (result/ok? bootstrap-result)
                    (let [bootstrap-metadata (:data bootstrap-result)
                          ;; Capture image SHA256 digest for evidence record (N11 §11 SHOULD)
                          image-digest (container-image-digest descriptor container-name)
                          ;; Surface the gate's non-blocking findings (rootless
                          ;; warning, host-mount review) on the environment record.
                          gate-warnings (get-in gate-result [:data :security/warnings])
                          gate-review   (get-in gate-result [:data :security/review])
                          metadata (cond-> (merge (get create-result :data {})
                                                   bootstrap-metadata)
                                     image-digest (assoc :image-digest image-digest)
                                     (seq gate-warnings) (assoc :security/warnings gate-warnings)
                                     (seq gate-review)   (assoc :security/review gate-review))]
                      (result/ok (proto/create-environment-record
                                  container-name (descriptor/kind descriptor) task-id workdir
                                  (assoc env-config :metadata metadata))))
                    bootstrap-result))
                create-result)))))))

  (execute! [_this environment-id command opts]
    (try
      (exec-in-container descriptor environment-id command opts)
      (catch Exception e
        (result/err :exec-failed (.getMessage e)))))

  (copy-to! [_this environment-id local-path remote-path]
    (try
      (copy-to-container descriptor environment-id local-path remote-path)
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (copy-from! [_this environment-id remote-path local-path]
    (try
      (copy-from-container descriptor environment-id remote-path local-path)
      (catch Exception e
        (result/err :copy-failed (.getMessage e)))))

  (release-environment! [_this environment-id]
    (try
      (stop-container descriptor environment-id default-stop-timeout)
      (remove-container descriptor environment-id)
      (catch Exception e
        (result/err :release-failed (.getMessage e)))))

  (environment-status [_this environment-id]
    (try
      (inspect-container descriptor environment-id)
      (catch Exception e
        (result/ok {:status :unknown :error (.getMessage e)}))))

  (persist-workspace! [_this environment-id opts]
    (let [exec! (container-exec-fn descriptor environment-id
                                   (get opts :workdir default-workdir))]
      (workspace/git-persist! exec! opts)))

  (restore-workspace! [_this environment-id opts]
    (let [exec! (container-exec-fn descriptor environment-id
                                   (get opts :workdir default-workdir))]
      (workspace/git-restore! exec! opts))))

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-oci-cli-executor
  "Create an OCI-CLI executor for the runtime kind named by `:runtime-kind`
   on the config map.

   Config:
   - :runtime-kind — :docker (default) or :podman. :nerdctl is known but
                     not yet supported.
   - :executable   — explicit path to the runtime CLI binary
   - :image        — container image for tasks (default from images.edn :default entry)
   - :network      — network name to attach to (legacy; execution-plan
                     network-profile takes precedence when present)"
  [config]
  (let [descriptor (descriptor/make-descriptor config)]
    (map->OciCliExecutor
     {:config     config
      :descriptor descriptor
      :image      (get config :image (images/default-image))
      :network    (:network config)})))

(defn create-docker-executor
  "Create a Docker-backed OCI-CLI executor.

   Preserved as the back-compat factory name for callers that predate
   N11-delta. Equivalent to `create-oci-cli-executor` with
   `{:runtime-kind :docker}`.

   Config:
   - :image       — container image for tasks (default from images.edn :default entry)
   - :network     — network to attach to
   - :docker-path — path to the docker binary (legacy alias for :executable)"
  [config]
  (create-oci-cli-executor (assoc config :runtime-kind :docker)))
