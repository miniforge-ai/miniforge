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
   [ai.miniforge.dag-executor.execution-plan :as execution-plan]
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
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.redaction.interface :as redaction]
   [clojure.java.io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; Configuration
;; ============================================================================
(def ^{:stratum 0} default-workdir "/workspace")

(def ^{:stratum 0} default-acquisition-timeout-ms
  "Default ceiling on `acquire-environment!` wall-clock duration. Picked
   so a cold-cache image pull (~60 s on a slow link for the runner
   images) still fits, while a stuck Docker daemon fails within two
   minutes instead of blocking the workflow indefinitely. Override per
   call via `env-config[:acquisition-timeout-ms]`. The 2026-05-16
   dogfood hung 33+ minutes on stuck Docker acquires before PR #895's
   test-side mock landed; the production-side guard lives here."
  120000)

(defn- ^{:stratum 0} acquire-failed-result
  [throwable]
  (result/err :acquire-failed
              (or (ex-message throwable) (messages/t :oci/acquire-failed))
              (cond-> {:surface :acquire-environment!
                       :exception-class (.getName (class throwable))}
                (ex-data throwable) (assoc :exception-data (ex-data throwable)))))

(def ^{:stratum 0} default-stop-timeout
  "Minimum graceful-stop timeout (seconds). Also the floor for the timeout
   computed from an execution plan's :time-limit-ms — `--stop-timeout`
   never goes below this."
  5)

(def ^{:stratum 0} default-exec-timeout-ms
  "Ceiling on a single `docker exec` invocation (`execute!`). Five minutes
   covers long-running agent commands while still bounding a hung pty or
   container-namespace teardown. Companion follow-up to the 2026-05-16
   dogfood fix: `acquire-environment!` was bounded by PR #895;
   `execute!`, `release-environment!`, `copy-to!`, and `copy-from!`
   are bounded here."
  300000)

(def ^{:stratum 0} default-copy-timeout-ms
  "Ceiling on a `docker exec`-based file-copy operation (`copy-to!` /
   `copy-from!`). Two minutes accommodates large workspace transfers
   while preventing an indefinite hang if Docker stalls mid-pipe."
  120000)

(def ^{:stratum 0} default-release-timeout-ms
  "Ceiling on container stop + remove during `release-environment!`.
   Two minutes bounds the stop/rm sequence that can hang on pty orphans
   or container-namespace teardown."
  120000)

(def ^{:stratum 0} container-name-prefix
  "Prefix for generated container names: <prefix><task-id-slice>."
  "miniforge-task-")

(def ^{:stratum 0} container-name-uuid-slice
  "Number of characters to take from the task UUID when constructing the
   container name. Eight is enough collision space for a single-host
   workflow."
  8)

;; Resource paths for Dockerfiles (for documentation/building images).
;; The Dockerfile syntax is OCI-standard; "docker/" in the resource path is
;; historical and stays unchanged in Phase 1.
(def ^{:stratum 0} dockerfile-resources
  {:minimal "executor/docker/Dockerfile.task-runner"
   :clojure "executor/docker/Dockerfile.task-runner-clojure"})

(defn- ^{:stratum 0} runtime-default-resources
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
(defn ^{:stratum 0} runtime-cmd
  "Build a runtime CLI invocation vector for the given descriptor."
  [descriptor & args]
  (apply vector (descriptor/executable descriptor) args))

(defn ^{:stratum 0} shell-quote
  "Single-quote a shell argument."
  [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn- ^{:stratum 0} env-file-entry?
  "True when an env entry fits an env file. Docker and Podman read one
   NAME=value line at a time and take the value literally, so the value
   must have no line break and the name must match
   `execution-plan/env-name-pattern`: a line break in it would start a
   second assignment the plan does not show, and a bare NAME line copies
   that variable from the runtime CLI's own environment."
  [[k v]]
  (and (re-find execution-plan/env-name-pattern (name k))
       (not (re-find #"[\r\n]" (str v)))))

(defn- ^{:stratum 0} write-env-file!
  "Write `entries` as NAME=value lines to a new temp file for `--env-file`.
   Files/createTempFile makes it owner-only (rw-------) on POSIX."
  ^java.io.File [entries]
  (let [path (java.nio.file.Files/createTempFile
              "miniforge-env-" ".env"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (spit (.toFile path) (str/join (for [[k v] entries] (str (name k) "=" v "\n"))))
    (.toFile path)))

(defn- ^{:stratum 0} run-with-runtime-env
  "Call (run-fn), then delete the env file `prepare-runtime-env!` wrote.
   An env it refused is a failed runtime call (exit 1) carrying the reason,
   and run-fn does not run."
  [{:keys [file error]} run-fn]
  (if error
    {:exit 1 :out "" :err error :out-bytes (byte-array 0) :err-bytes (byte-array 0)}
    (try
      (run-fn)
      (finally
        (when file (.delete ^java.io.File file))))))

(def ^{:stratum 0} untrusted-env-names
  "Env vars an untrusted capsule takes from env-config whatever their value:
   what a shell and its locale need. LC_* and CLAUDE_CODE_* knobs pass by
   prefix (`untrusted-env-allowed?`)."
  #{"PATH" "HOME" "USER" "LOGNAME" "SHELL" "LANG" "LANGUAGE" "TERM" "COLORTERM"
    "TZ" "TMPDIR" "CI" "NO_COLOR"})

(def ^{:stratum 0} credential-word-pattern
  "A `_`-separated word that makes a CLAUDE_CODE_* variable a credential
   (CLAUDE_CODE_OAUTH_TOKEN, CLAUDE_CODE_CLIENT_KEY_PASSPHRASE) rather than
   a knob (CLAUDE_CODE_MAX_OUTPUT_TOKENS)."
  #"(?i)(?:^|_)(?:TOKEN|KEY|SECRET|PASSWORD|PASSPHRASE|CREDENTIALS?|AUTHORIZATION|OAUTH|COOKIE)(?:_|$)")

(def ^{:stratum 0} proxy-env-names
  "Proxy variables curl and git read, in both cases. Docker adds them to a
   container from the client's ~/.docker/config.json, and Podman from its
   own environment, unless the launch sets the name with -e."
  (into #{} (mapcat (juxt identity str/lower-case))
        ["HTTP_PROXY" "HTTPS_PROXY" "FTP_PROXY" "ALL_PROXY" "NO_PROXY"]))

(def ^{:stratum 0} agent-credential-names
  "Env vars N11 §8.1 injects into every capsule, whatever its trust level,
   for the agent itself: ANTHROPIC_API_KEY, the primary secret for current
   tasks. `filter-env-by-trust` keeps these, and `build-launch-plan` adds
   the agent backend's own (`llm/backend-credential-env`)."
  #{"ANTHROPIC_API_KEY"})

(def ^{:stratum 0} git-config-env-pattern
  "GIT_CONFIG_COUNT / GIT_CONFIG_KEY_n / GIT_CONFIG_VALUE_n: one config tuple
   whose values can carry a credential (an Authorization header)."
  #"GIT_CONFIG_(COUNT|KEY_\d+|VALUE_\d+)")

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

(defn- ^{:stratum 0} normalize-image-id
  "Normalize a runtime-printed image ID to the `sha256:<64hex>` digest form.
   Docker prints IDs as `sha256:<hex>`; Podman prints the bare `<hex>`. The
   digest gate and the evidence record both expect the prefixed form."
  [id]
  (when-let [id (some-> id str/trim not-empty)]
    (let [id (str/lower-case id)]
      (if (re-matches #"^[a-f0-9]{64}$" id)
        (str "sha256:" id)
        id))))

;; ============================================================================
;; Image Management
;; ============================================================================
(def ^{:stratum 0} task-runner-images
  "Pre-defined task runner images that can be built from bundled Dockerfiles.
   Sourced from `runtime/images.edn` so image references stay
   configuration-not-code. Resolved at namespace load and exposed as a map
   for callers that expect map semantics."
  (images/task-runner-images))

(defn ^{:stratum 0} find-dockerfile-path
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

(defn- ^{:stratum 0} chain-container-command
  [result cmd-fn]
  (if (result/ok? result)
    (cmd-fn)
    result))

;; ============================================================================
;; Workspace Bootstrap (N11 §4.2)
;; ============================================================================
(defn- ^{:stratum 0} infer-host-kind
  "Infer the git hosting provider from a repository URL.
   Returns :github, :gitlab, or :generic."
  [repo-url]
  (cond
    (str/includes? repo-url "github.com")  :github
    (str/includes? repo-url "gitlab.com")  :gitlab
    (str/includes? repo-url "gitlab")      :gitlab
    :else                                  :generic))

(defn- ^{:stratum 0} resolve-git-token
  "Resolve a git authentication token for capsule clone.
   Delegates to config/resolve-token which uses the profile + env chain:
   MINIFORGE_GIT_TOKEN → profile → provider env var → CLI fallback."
  [_repo-url {:keys [host-kind]}]
  (config/resolve-token host-kind))

(def ^{:stratum 0} ^:private git-auth-users
  "HTTP basic-auth username each host kind pairs with a token; hosts not
   listed take `default-git-auth-user`."
  {:gitlab "oauth2"})

(def ^{:stratum 0} ^:private default-git-auth-user "x-access-token")

(defn- ^{:stratum 0} basic-auth-header
  "HTTP Basic Authorization header carrying `token` for `user`."
  [user token]
  (str "Authorization: Basic "
       (.encodeToString (java.util.Base64/getEncoder)
                        (.getBytes (str user ":" token) "UTF-8"))))

(def ^{:stratum 0} ^:private git-credential-config
  "Config every credentialed git command runs with, set through the
   environment, which overrides the workspace's .git/config (written by
   untrusted code): no hooks, no credential helper, and https as the only
   transport, so no hook, helper, ssh command or ext:: transport named in
   that config runs while the credential is in git's environment."
  [["core.hooksPath" "/dev/null"]
   ["credential.helper" ""]
   ["protocol.allow" "never"]
   ["protocol.https.allow" "always"]])

(defn- ^{:stratum 0} git-config-env
  "GIT_CONFIG_COUNT / GIT_CONFIG_KEY_n / GIT_CONFIG_VALUE_n for `entries`,
   a seq of [key value]."
  [entries]
  (into {"GIT_CONFIG_COUNT" (str (count entries))}
        (mapcat (fn [i [k v]] [[(str "GIT_CONFIG_KEY_" i) k] [(str "GIT_CONFIG_VALUE_" i) v]])
                (range) entries)))

(defn- ^{:stratum 0} percent-decode
  "Decode %XX escapes in a URL component; `+` stays a plus."
  [s]
  (java.net.URLDecoder/decode (str/replace s "+" "%2B") "UTF-8"))

(defn- ^{:stratum 0} credential-git-check
  "`version-result`, from `git --version` in the capsule, or an error when
   that git predates 2.31: it ignores GIT_CONFIG_COUNT, which carries the
   credential, so a private clone would fail without saying why. Output
   it cannot parse passes."
  [version-result]
  (let [out (get-in version-result [:data :stdout] "")]
    (if-let [[_ major minor] (re-find #"git version (\d+)\.(\d+)" out)]
      (if (neg? (compare [(parse-long major) (parse-long minor)] [2 31]))
        (result/err :git-too-old (messages/t :oci/git-too-old {:version (str/trim out)}))
        version-result)
      version-result)))

(defn- ^{:stratum 0} git-network-command?
  "True for the git commands persist/restore send to the remote."
  [cmd]
  (and (vector? cmd)
       (= "git" (first cmd))
       (contains? #{"push" "fetch"} (second cmd))))

;; ============================================================================
;; Capsule security gate — runtime enforcement of the runtime-* policies
;; ============================================================================
(defn- ^{:stratum 0} normalize-plan-env
  "Normalize an env-config :env map to the string→string shape
   ExecutionPlanSchema requires: keyword keys via `name`, values via `str`
   (a numeric port or boolean flag becomes its printed form — the same
   text the container would see on the wire). The plan is the canonical,
   schema-conformant record of the launch, so both normalize here — once,
   at the writer — rather than at every reader."
  [env]
  (into {}
        (map (fn [[k v]] [(name k) (str v)]))
        env))

(defn- ^{:stratum 0} workspace-opts
  "persist/restore opts. With a recorded remote, push and fetch target its
   repository URL rather than the capsule's `origin`, which untrusted code
   can repoint at another repository on the same host."
  [opts git-remotes environment-id]
  (if-let [url (some-> git-remotes deref (get-in [environment-id :https-url]))]
    (assoc opts :remote url)
    opts))

(defn- ^{:stratum 0} git-config-entries
  "The [key value] pairs GIT_CONFIG_COUNT / KEY_n / VALUE_n in `env` set,
   in order."
  [env]
  (for [i (range (parse-long (get env "GIT_CONFIG_COUNT" "0")))]
    [(get env (str "GIT_CONFIG_KEY_" i)) (get env (str "GIT_CONFIG_VALUE_" i))]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} untrusted-env-allowed?
  "True when an untrusted capsule may take env var `n` from env-config:
   `untrusted-env-names`, LC_*, or a CLAUDE_CODE_* knob with no
   `credential-word-pattern` word."
  [n]
  (or (contains? untrusted-env-names n)
      (str/starts-with? n "LC_")
      (and (str/starts-with? n "CLAUDE_CODE_")
           (not (re-find credential-word-pattern n)))))

(defn ^{:stratum 1} prepare-runtime-env!
  "Deliver env-map to the container without putting its values on the
   runtime argv (`ps` shows argv to every host user) or into the runtime
   CLI's own environment (where LD_PRELOAD or DOCKER_DEFAULT_PLATFORM would
   change the CLI itself): entries go to an owner-only temp file passed as
   `--env-file`. Returns {:args :file}; the caller deletes :file with
   `run-with-runtime-env`. An env file holds one line per entry, and every
   other channel exposes the value, so env-map with an entry that does not
   fit one line (`env-file-entry?`) is refused: {:error message}, and
   nothing is written."
  [env-map]
  (if-let [bad (seq (remove env-file-entry? env-map))]
    {:error (messages/t :oci/env-line-break {:names (str/join ", " (map (comp pr-str name key) bad))})}
    (let [file (when (seq env-map) (write-env-file! env-map))]
      {:args (when file ["--env-file" (str file)])
       :file file})))

(defn- ^{:stratum 1} split-userinfo
  "[url auth] for an http(s) URL: the URL without any `user[:password]@`,
   and {:user :token} when it carried a password, else nil."
  [url]
  (if-let [[_ scheme userinfo more] (re-matches #"(?i)(https?://)([^/]*)@(.*)" url)]
    (let [[user password] (str/split userinfo #":" 2)]
      [(str scheme more)
       (when password {:user (percent-decode user) :token (percent-decode password)})])
    [url nil]))

(defn- ^{:stratum 1} call-acquire-body
  [body-fn]
  (try
    (body-fn)
    (catch Throwable t
      (acquire-failed-result t))))

(defn- ^{:stratum 1} run-runtime-timed
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

(defn- ^{:stratum 1} run-runtime-process-timed
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

(defn- ^{:stratum 1} git-auth
  "{:user :token} that authenticates git to `remote`: the credential its URL
   carried, else the token resolved for its host kind. A resolved token is
   never sent to a plain-http remote. nil without one."
  [{:keys [repo-url https-url host-kind url-auth]}]
  (or url-auth
      (when-let [token (when-not (str/starts-with? (str/lower-case (str https-url)) "http://")
                         (resolve-git-token repo-url {:host-kind host-kind}))]
        {:user  (get git-auth-users host-kind default-git-auth-user)
         :token token})))

(defn- ^{:stratum 1} git-credential-env
  "Env that authenticates one git command to the host of `https-url` with
   `auth`: an Authorization header set through GIT_CONFIG_COUNT/KEY/VALUE
   (git 2.31+), scoped by `http.<origin>/.extraheader` so git sends it to
   that host only, plus `git-credential-config` and no askpass program.
   extraheader is multi-valued, so an empty entry first drops any header the
   image or workspace config set for that host; otherwise git would send
   two Authorization headers.
   Env-supplied config is never written to .git/config, and exec passes the
   values in an env file, so the token lands in neither the capsule's repo
   state nor the runtime argv (N11 §2.2). An http origin also allows the
   http transport. Empty without a token or for a URL that is not http(s)."
  [https-url {:keys [user token]}]
  (if-let [[_ origin scheme] (when token (re-find #"^((https?)://[^/]+)(?:/|$)" https-url))]
    (merge (git-config-env (conj (cond-> git-credential-config
                                   (= "http" scheme) (conj ["protocol.http.allow" "always"]))
                                 [(str "http." origin "/.extraheader") ""]
                                 [(str "http." origin "/.extraheader")
                                  (basic-auth-header user token)]))
           {"GIT_TERMINAL_PROMPT" "0"
            "GIT_ASKPASS"         ""})
    {}))

(defn- ^{:stratum 1} without-git-config
  "`env` without its GIT_CONFIG_* tuple."
  [env]
  (into {} (remove (fn [[k _]] (re-matches git-config-env-pattern (name k)))) env))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} untrusted-env-blanks
  "Empty values that override, in an untrusted capsule, what it would get
   from outside env-config: every proxy variable (`proxy-env-names`), and
   each name in `image-env` (the image's ENV) that the redaction policy
   calls a secret or that is GIT_CONFIG_PARAMETERS or in a GIT_CONFIG_*
   tuple, unless `keep-names` or `untrusted-env-allowed?` allow it."
  [image-env keep-names]
  (into (zipmap proxy-env-names (repeat ""))
        (for [n image-env
              :when (and (not (contains? keep-names n))
                         (not (untrusted-env-allowed? n))
                         (or (redaction/secret-key? n)
                             (re-matches git-config-env-pattern n)
                             (= "GIT_CONFIG_PARAMETERS" n)))]
          [n ""])))

(defn ^{:stratum 2} filter-env-by-trust
  "Return env-map as an untrusted capsule may have it, unless trust-level is
   :trusted or :privileged; :untrusted, nil and any unknown level all filter.
   `build-launch-plan` applies it to the container env, which every process
   in the capsule inherits; host-side callers that need a secret for one
   command (release's GH_TOKEN for `gh`) pass it on that exec instead.

   Default deny: an entry stays only when its name is in `keep-names` (the
   agent's own credentials, `agent-credential-names` unless given, which
   N11 §8.1 injects at every trust level) or `untrusted-env-allowed?`. A
   proxy variable (`proxy-env-names`) stays without any `user:password@`
   in its URL, so an authenticating proxy needs a trusted launch."
  ([env-map trust-level] (filter-env-by-trust env-map trust-level agent-credential-names))
  ([env-map trust-level keep-names]
   (if (contains? #{:trusted :privileged} trust-level)
     env-map
     (into {} (keep (fn [[k v :as entry]]
                      (let [n (name k)]
                        (cond
                          (contains? proxy-env-names n)
                          [k (str/replace (str v) #"^([A-Za-z][A-Za-z0-9+.-]*://)?[^/@]*@" "$1")]
                          (or (contains? keep-names n) (untrusted-env-allowed? n)) entry))))
           env-map))))

(defn- ^{:stratum 2} git-remote
  "The repo to bootstrap, from env-config: {:repo-url :https-url :host-kind},
   plus :url-auth when :repo-url carried `user:password@`, or nil when
   env-config has no :repo-url. Userinfo is stripped from both URLs, so it
   reaches neither the clone's argv nor origin's config. SSH URLs become
   HTTPS, since containers lack an SSH agent. The executor keeps this, not
   anything read back from the capsule, as the repository its git
   credential is for, so repointing origin inside the capsule cannot
   redirect the credential."
  [env-config]
  (when-let [given-url (:repo-url env-config)]
    (let [[repo-url url-auth] (split-userinfo given-url)]
      (cond-> {:repo-url  repo-url
               :https-url (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" repo-url)]
                            (str "https://" host "/" path)
                            repo-url)
               ;; `or`, not a get default: an explicit nil :host-kind means
               ;; infer it, as bootstrap always treated it.
               :host-kind (or (:host-kind env-config) (infer-host-kind repo-url))}
        url-auth (assoc :url-auth url-auth)))))

(defn ^{:stratum 2} with-acquisition-timeout
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

(defn ^{:stratum 2} run-runtime
  "Execute a runtime CLI command and return the result.
   Internal callers that need a bounded deadline use `run-runtime-timed`
   directly; this public entry point preserves the unbounded signature
   for backward-compatibility with call sites already covered by an outer
   `with-acquisition-timeout` guard."
  [descriptor & args]
  (apply run-runtime-timed nil descriptor args))

(defn ^{:stratum 2} run-runtime-process
  "Execute a runtime CLI command with optional stdin bytes.
   Internal callers that need a bounded deadline use `run-runtime-process-timed`
   directly; this public entry point is unbounded for backward-compatibility."
  [descriptor args & {:keys [stdin-bytes]}]
  (run-runtime-process-timed nil descriptor args :stdin-bytes stdin-bytes))

(defn ^{:stratum 2} exec-in-container
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

(defn ^{:stratum 2} copy-to-container
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

(defn ^{:stratum 2} copy-from-container
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

(defn ^{:stratum 2} stop-container
  "Stop a running container."
  [descriptor container-id timeout]
  (run-runtime-timed default-release-timeout-ms descriptor "stop" "-t" (str timeout) container-id))

(defn ^{:stratum 2} remove-container
  "Remove a container (force)."
  [descriptor container-id]
  (let [result (run-runtime-timed default-release-timeout-ms descriptor "rm" "-f" container-id)]
    (result/ok {:released? (zero? (:exit result))})))

(defn- ^{:stratum 2} remote-credential-env
  "Credential env for the git remote recorded when `environment-id` was
   bootstrapped, or {} when none was recorded."
  [git-remotes environment-id]
  (if-let [remote (some-> git-remotes deref (get environment-id))]
    (git-credential-env (:https-url remote) (git-auth remote))
    {}))

(defn- ^{:stratum 2} combine-git-env
  "`base` then `extra`, with their GIT_CONFIG_* entries renumbered into one
   block, `extra`'s after `base`'s so its settings win, and their other
   variables merged, `extra` winning."
  [base extra]
  (let [entries (concat (git-config-entries base) (git-config-entries extra))]
    (cond-> (merge (without-git-config base) (without-git-config extra))
      (seq entries) (merge (git-config-env entries)))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} build-launch-plan
  "Assemble the execution plan for a pending launch from what the launch site
   actually knows: the resolved image digest, the caller's mounts / env /
   trust level from env-config, and the capsule's keep-alive command. Optional
   fields (:time-limit-ms :memory-limit-mb :network-profile :secrets-refs)
   pass through only when env-config carries them — absent fields stay absent
   rather than being filled with guesses. The :env keys are normalized to
   strings so the plan conforms to ExecutionPlanSchema, and secrets are
   dropped for an :untrusted plan (`filter-env-by-trust`). A nil or unknown
   :trust-level becomes :untrusted, so neither the secret filter nor the
   read-only mount rule can be skipped by a malformed value. The filter keeps
   the agent's credentials: `agent-credential-names` and those of the agent
   backend, env-config's :agent-backend or else the configured [:llm :backend],
   plus the names env-config's :env-passthrough lists. An :untrusted plan
   also sets `untrusted-env-blanks` for `image-env`, the names the image's
   ENV sets, under any value env-config gives.

   This one plan is both what `check-plan-security` inspects and what
   `create-container` receives as :execution-plan, so the plan the gate
   checked is the plan that runs."
  ([image-digest env-config] (build-launch-plan image-digest env-config #{}))
  ([image-digest env-config image-env]
   (let [trust-level (get execution-plan/trust-levels (:trust-level env-config) :untrusted)
         ;; `or`, not a get default: the user config is read only when
         ;; env-config names no backend.
         backend     (or (:agent-backend env-config)
                         (get-in (config/load-merged-config) [:llm :backend]))
         keep-names  (-> agent-credential-names
                         (into (llm/backend-credential-env backend))
                         (into (map name) (:env-passthrough env-config)))
         env         (filter-env-by-trust (normalize-plan-env (get env-config :env {}))
                                          trust-level keep-names)]
     (cond-> {:image-digest image-digest
              :command      ["sleep" "infinity"]
              :mounts       (get env-config :mounts [])
              :env          (cond->> env
                              (= :untrusted trust-level)
                              (merge (untrusted-env-blanks image-env keep-names)))
              :trust-level  trust-level}
       (:time-limit-ms env-config)   (assoc :time-limit-ms (:time-limit-ms env-config))
       (:memory-limit-mb env-config) (assoc :memory-limit-mb (:memory-limit-mb env-config))
       (:network-profile env-config) (assoc :network-profile (:network-profile env-config))
       (:secrets-refs env-config)    (assoc :secrets-refs (:secrets-refs env-config))))))

(defn- ^{:stratum 3} with-git-credentials
  "execute! opts: when they ask for :git-credentials?, the recorded remote's
   credential env joins :env. Release's in-capsule fetch and push use this;
   since origin carries no credential, they are unauthenticated without it.
   The two GIT_CONFIG_* blocks are combined (`combine-git-env`), the
   caller's last, so a caller's own git config, such as release's push
   retry, is kept and wins."
  [opts git-remotes environment-id]
  (if (:git-credentials? opts)
    (update opts :env #(combine-git-env (remote-credential-env git-remotes environment-id) %))
    opts))

;; ============================================================================
;; Container Operations
;; ============================================================================
(defn ^{:stratum 3} create-container
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

(defn ^{:stratum 3} inspect-container
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

(defn ^{:stratum 3} container-image-digest
  "Return the SHA256 image digest for a container, or nil on failure."
  [descriptor container-name]
  (try
    (let [inspect-result (run-runtime descriptor
                                      "inspect" container-name
                                      "--format" "{{.Image}}")]
      (when (zero? (:exit inspect-result))
        (normalize-image-id (:out inspect-result))))
    (catch Exception _ nil)))

(defn ^{:stratum 3} image-config-digest
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

(defn ^{:stratum 3} image-env-names
  "The names the ENV of local image `image` sets, or nil on failure."
  [descriptor image]
  (let [result (run-runtime descriptor "image" "inspect" image
                            "--format" "{{range .Config.Env}}{{println .}}{{end}}")]
    (when (zero? (:exit result))
      (into #{} (keep #(second (re-find #"^([^=]+)=" %))) (str/split-lines (:out result))))))

(defn ^{:stratum 3} image-exists?
  "Check if a container image exists locally."
  [descriptor image]
  (let [result (run-runtime descriptor "image" "inspect" image)]
    (zero? (:exit result))))

(defn ^{:stratum 3} image-repo-digest
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

(defn ^{:stratum 3} pull-image!
  "Pull `image` via the runtime. Best-effort: a failed pull (e.g. a
   locally-built image with no registry) leaves the image absent, which the
   caller surfaces as a nil digest."
  [descriptor image]
  (try (run-runtime descriptor "pull" image) (catch Exception _ nil)))

(defn ^{:stratum 3} build-image!
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

(defn- ^{:stratum 3} container-exec-fn
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

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} security-gate-check
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
   :execution-plan.

   `image-env-fn` (injected; by default it reports an empty ENV) gives the
   names the pinned image's ENV sets, which an :untrusted plan blanks
   (`untrusted-env-blanks`). An :untrusted launch whose image ENV cannot be
   read fails closed."
  ([descriptor image env-config digest-fn]
   (security-gate-check descriptor image env-config digest-fn (constantly #{})))
  ([descriptor image env-config digest-fn image-env-fn]
   (let [digest          (digest-fn descriptor image)
         image-env       (when digest (image-env-fn descriptor digest))
         plan            (build-launch-plan digest env-config image-env)
         security-config {:host-path-allowlist #{(get env-config :workdir default-workdir)}
                          :rootless-action     plan-security/default-rootless-action}
         decision        (plan-security/check-plan-security plan descriptor security-config)]
     (cond
       (response/anomaly-map? decision)
       (result/err :security-policy-violation
                   (:anomaly/message decision)
                   {:security/findings (:security/findings decision)})

       (and (= :untrusted (:trust-level plan)) (nil? image-env))
       (result/err :security-policy-violation (messages/t :oci/image-env-unreadable {:image image}))

       :else
       (result/ok (assoc (:output decision) :launch-plan plan))))))

(defn ^{:stratum 4} resolve-image-digest!
  "Resolve the pinned `sha256:<64hex>` digest for `image`, pulling it first when
   it is not present locally so `image inspect` can read it (inspect does not
   pull). Returns nil only when the image cannot be resolved even after a pull —
   a genuinely unpinnable image, which the security gate then blocks. This is
   the production digest resolver injected into `security-gate-check`."
  [descriptor image]
  (when-not (image-exists? descriptor image)
    (pull-image! descriptor image))
  (image-config-digest descriptor image))

(defn ^{:stratum 4} ensure-image!
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

(defn- ^{:stratum 4} bootstrap-workspace!
  "Clone a git repository into the container workspace after creation.
   Runs git clone, configures git identity, and checks out the target branch.
   Returns a result map with bootstrap metadata. No-op success when
   repo-url is nil (local mode / pre-existing workspace).
   Uses config-based token resolution (profile + env fallback) rather than
   URL-guessing. With a credential, checks the image's git can take it
   (`credential-git-check`), clones the plain URL and hands the credential
   to the clone command alone (`git-credential-env`): origin's URL in the
   capsule carries none, and no push URL is set. Clones every branch, so
   release can compare against a base other than the checked-out branch."
  [descriptor container-name workdir env-config]
  (if-let [{:keys [repo-url https-url] :as remote} (git-remote env-config)]
    (let [branch    (get env-config :branch "main")
          auth      (git-auth remote)
          clone-url (if auth https-url repo-url)
          exec!  (container-exec-fn descriptor container-name "/"
                                    :error-on-failure? true
                                    :error-prefix (messages/t :oci/bootstrap-failed)
                                    :sanitize? true)
          clone-cmd ["git" "clone" "--branch" (str branch) "--" clone-url workdir]
          result (-> (if auth (credential-git-check (exec! ["git" "--version"])) (result/ok nil))
                     (chain-container-command #(exec! clone-cmd (git-credential-env https-url auth)))
                     ;; Use --local (not --global) since container rootfs is read-only
                     (chain-container-command
                      #(exec! ["git" "-C" workdir "config" "user.email" "miniforge@miniforge.ai"]))
                     (chain-container-command
                      #(exec! ["git" "-C" workdir "config" "user.name" "miniforge"])))]
      (if (result/err? result)
        result
        (let [sha-r (exec! ["git" "-C" workdir "rev-parse" "HEAD"])]
          (if (result/ok? sha-r)
            (let [base-sha (str/trim (get-in sha-r [:data :stdout] ""))]
              (result/ok (cond-> {:base-branch (str branch)}
                           (seq base-sha) (assoc :base-sha base-sha))))
            sha-r))))
    (result/ok nil)))

(defn- ^{:stratum 4} workspace-exec-fn
  "exec-fn for `workspace/git-persist!` and `git-restore!`. `cred-env` is a
   delay that only push and fetch force: a persist with nothing to push
   resolves no token, and add, status and commit, which run hooks and
   fsmonitor the untrusted workspace defines, never see the credential."
  [descriptor container-id workdir cred-env]
  (let [exec! (container-exec-fn descriptor container-id workdir)]
    (fn [cmd]
      (exec! cmd (when (git-network-command? cmd) @cred-env)))))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} ensure-all-images!
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
;; OciCliExecutor Record
;; ============================================================================
(defrecord ^{:stratum 5} OciCliExecutor [config descriptor image network git-remotes]
  ;; `git-remotes` is an atom of environment-id -> `git-remote`, recorded at
  ;; bootstrap so persist/restore can authenticate push and fetch per
  ;; command; nil (as in tests that build the record directly) disables it.
  ;;
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
        (let [gate-result (security-gate-check descriptor image env-config
                                               resolve-image-digest! image-env-names)]
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
                                     (seq gate-review)   (assoc :security/review gate-review))
                          remote   (git-remote env-config)]
                      (when remote
                        (some-> git-remotes (swap! assoc container-name remote)))
                      ;; The record is returned to callers, so it carries the
                      ;; repo URL without any user:password@ it was given.
                      (result/ok (proto/create-environment-record
                                  container-name (descriptor/kind descriptor) task-id workdir
                                  (cond-> (assoc env-config :metadata metadata)
                                    remote (assoc :repo-url (:repo-url remote))))))
                    ;; The caller gets no environment id to release, so
                    ;; remove the container that started.
                    (do (remove-container descriptor container-name)
                        bootstrap-result)))
                create-result)))))))

  (execute! [_this environment-id command opts]
    (try
      (exec-in-container descriptor environment-id command
                         (with-git-credentials opts git-remotes environment-id))
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
      (let [removed (remove-container descriptor environment-id)]
        ;; Keep the recorded remote until the container is really gone: a
        ;; capsule that is still there may still have work to persist.
        (when (get-in removed [:data :released?])
          (some-> git-remotes (swap! dissoc environment-id)))
        removed)
      (catch Exception e
        (result/err :release-failed (.getMessage e)))))

  (environment-status [_this environment-id]
    (try
      (inspect-container descriptor environment-id)
      (catch Exception e
        (result/ok {:status :unknown :error (.getMessage e)}))))

  (persist-workspace! [_this environment-id opts]
    (let [cred-env (delay (remote-credential-env git-remotes environment-id))
          exec!    (workspace-exec-fn descriptor environment-id
                                      (get opts :workdir default-workdir) cred-env)]
      (workspace/git-persist! exec! (workspace-opts opts git-remotes environment-id))))

  (restore-workspace! [_this environment-id opts]
    (let [cred-env (delay (remote-credential-env git-remotes environment-id))
          exec!    (workspace-exec-fn descriptor environment-id
                                      (get opts :workdir default-workdir) cred-env)]
      (workspace/git-restore! exec! (workspace-opts opts git-remotes environment-id)))))

;------------------------------------------------------------------------------ Layer 6

;; ============================================================================
;; Factory
;; ============================================================================
(defn ^{:stratum 6} create-oci-cli-executor
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
     {:config      config
      :descriptor  descriptor
      :image       (get config :image (images/default-image))
      :network     (:network config)
      :git-remotes (atom {})})))

;------------------------------------------------------------------------------ Layer 7

(defn ^{:stratum 7} create-docker-executor
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
