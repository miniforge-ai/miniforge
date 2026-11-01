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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.container
  "Container operations for the OCI-CLI executor: launch arguments, create,
   exec, copy, stop, remove and inspect."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.env
    :refer [env-inspect-format parse-inspected-env prepare-runtime-env! proxy-env-names
            run-with-runtime-env]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.git-credentials
    :refer [git-config-count]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process
    :refer [default-copy-timeout-ms default-exec-timeout-ms default-release-timeout-ms
            default-stop-timeout run-runtime run-runtime-process run-runtime-process-timed
            run-runtime-timed shell-quote]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.registry :as registry]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.java.io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} container-name-prefix
  "Prefix for generated container names: <prefix><task-id-slice>."
  "miniforge-task-")

(def ^{:stratum 0} container-name-uuid-slice
  "Number of characters to take from the task UUID when constructing the
   container name. Eight is enough collision space for a single-host
   workflow."
  8)

(defn- ^{:stratum 0} runtime-default-resources
  "Per-runtime memory/cpu defaults sourced from the registry. Wrapping the
   two lookups in a tiny constructor keeps the resource-arg builder a flat
   key-value map."
  [descriptor]
  (let [k (descriptor/kind descriptor)]
    {:memory (registry/default k :memory)
     :cpu    (registry/default k :cpu)}))

(defn ^{:stratum 0} build-mount-args
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

(defn ^{:stratum 0} build-security-args
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

(defn ^{:stratum 0} build-runtime-fs-args
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
;; Container command execution helper
;; ============================================================================
(defn- ^{:stratum 0} sanitize-token
  "Remove token credentials from a string for safe logging.
   Handles both GitHub (x-access-token) and GitLab (oauth2) auth formats."
  [s]
  (-> s
      (str/replace #"x-access-token:[^\s@]+" "x-access-token:***")
      (str/replace #"oauth2:[^\s@]+" "oauth2:***")))

(defn ^{:stratum 0} chain-container-command
  [result cmd-fn]
  (if (result/ok? result)
    (cmd-fn)
    result))

(defn ^{:stratum 0} exec-in-container
  "Execute a command in a running container."
  [descriptor container-id command opts]
  (let [cmd-args (if (string? command)
                   ["sh" "-c" command]
                   command)
        workdir-args (when (:workdir opts) ["-w" (:workdir opts)])
        env (prepare-runtime-env! (:env opts))
        full-args (concat ["exec"]
                          workdir-args
                          (:args env)
                          [container-id]
                          cmd-args)
        timeout-ms (if (contains? opts :timeout-ms)
                     (:timeout-ms opts)
                     default-exec-timeout-ms)
        start-time (System/currentTimeMillis)
        result (run-with-runtime-env
                env #(run-runtime-process-timed timeout-ms descriptor full-args))]
    (result/ok {:exit-code (:exit result)
                :stdout (:out result)
                :stderr (:err result)
                :duration-ms (- (System/currentTimeMillis) start-time)})))

(defn ^{:stratum 0} copy-to-container
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

(defn ^{:stratum 0} copy-from-container
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

(defn ^{:stratum 0} stop-container
  "Stop a running container."
  [descriptor container-id timeout]
  (run-runtime-timed default-release-timeout-ms descriptor "stop" "-t" (str timeout) container-id))

(defn ^{:stratum 0} remove-container
  "Remove a container (force)."
  [descriptor container-id]
  (let [result (run-runtime-timed default-release-timeout-ms descriptor "rm" "-f" container-id)]
    (result/ok {:released? (zero? (:exit result))})))

(defn ^{:stratum 0} container-git-count
  "The GIT_CONFIG_COUNT of the container's own environment: its image ENV
   and launch env as the runtime recorded them, which no process in the
   capsule can change. Each entry is read whole (`parse-inspected-env`), so
   a multi-line value cannot fake a count. 0 when none is set or inspect
   fails."
  [descriptor container-id]
  (let [r (run-runtime-timed default-exec-timeout-ms descriptor "inspect" "--format"
                             env-inspect-format container-id)]
    (try
      (if (zero? (:exit r)) (git-config-count (parse-inspected-env (:out r))) 0)
      (catch Exception _ 0))))

(defn ^{:stratum 0} inspect-container
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-resource-args
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

(defn ^{:stratum 1} container-exec-fn
  "Build a function that executes commands inside a container.
   Returns (fn [cmd] -> result-map); the (fn [cmd env]) arity adds `env`
   to that one command's environment.

   Options:
   - :error-on-failure? — return result/err on non-zero exit (default false)
   - :error-prefix — prefix for result error messages
   - :sanitize? — sanitize tokens in error messages (default false)"
  [descriptor container-id workdir
   & {:keys [error-on-failure? error-prefix sanitize?]
      :or {error-on-failure? false error-prefix (messages/t :oci/command-failed) sanitize? false}}]
  (fn exec!
    ([cmd] (exec! cmd nil))
    ([cmd env]
     (let [r (exec-in-container descriptor container-id cmd
                                (cond-> {:workdir workdir} (seq env) (assoc :env env)))]
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
         r)))))

;------------------------------------------------------------------------------ Layer 2

;; ============================================================================
;; Container Operations
;; ============================================================================
(defn ^{:stratum 2} create-container
  "Create and start a container.

   When execution-plan is present, it is authoritative for plan-owned launch
   settings: security flags, runtime filesystems, mounts, networking, time
   limit, and command. Absent plan fields do not fall back to the legacy
   `network` argument; a partial plan therefore means no explicit network
   flag and uses the default keep-alive command when :command is absent.
   Production launch plans conform to
   ai.miniforge.dag-executor.execution-plan/ExecutionPlanSchema, while this
   compatibility boundary also accepts partial plan maps.

   An :untrusted plan's proxy variables go on argv as `-e`: Docker adds the
   client's configured proxies for any name -e does not set, even one the
   env file sets. The plan holds them without credentials
   (`filter-env-by-trust`) or empty (`untrusted-env-blanks`).

   Returns {:container-id string :container-name string} on success."
  [descriptor container-name image workdir env-map resources network
   & {:keys [execution-plan]}]
  (let [effective-env (if (and (some? execution-plan)
                               (contains? execution-plan :env))
                        (:env execution-plan)
                        env-map)
        argv-env      (when (= :untrusted (:trust-level execution-plan))
                        (select-keys effective-env proxy-env-names))
        env           (prepare-runtime-env! (apply dissoc effective-env (keys argv-env)))
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
                              (mapcat (fn [[k v]] ["-e" (str k "=" v)]) (sort argv-env))
                              (:args env)
                              resource-args
                              (into [image] (get execution-plan :command ["sleep" "infinity"])))
        result        (run-with-runtime-env
                       env #(run-runtime-process descriptor cmd-args))]
    (if (zero? (:exit result))
      (result/ok {:container-id (str/trim (:out result))
                  :container-name container-name})
      (result/err :container-create-failed (:err result)))))
