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
   and the executor picks it up without code changes.

   The executor record and its constructors live here. The definitions they
   are built from are split by concern (rule 210) into `oci-cli.process`,
   `oci-cli.env`, `oci-cli.git-credentials`, `oci-cli.image`,
   `oci-cli.container`, `oci-cli.capsule-git`, `oci-cli.acquisition` and
   `oci-cli.launch`; this namespace refers to them."
  (:require
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.images :as images]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.acquisition
    :refer [default-acquisition-timeout-ms with-acquisition-timeout without-interrupt]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.capsule-git
    :refer [bootstrap-workspace! remote-credential-env with-git-credentials workspace-exec-fn
            workspace-opts]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.container
    :refer [container-name-prefix container-name-uuid-slice copy-from-container copy-to-container
            create-container exec-in-container inspect-container remove-container stop-container]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.git-credentials
    :refer [git-remote]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.image
    :refer [container-image-digest ensure-image! image-env image-exists? resolve-image-digest!
            task-runner-images]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.launch
    :refer [default-workdir security-gate-check]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process
    :refer [default-stop-timeout]]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.dag-executor.workspace :as workspace]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} ensure-all-images!
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
(defrecord ^{:stratum 0} OciCliExecutor [config descriptor image network git-remotes]
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
                                               resolve-image-digest! image-env)]
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
                    (let [clone-url (get-in bootstrap-result [:data :clone-url])
                          bootstrap-metadata (dissoc (:data bootstrap-result) :clone-url)
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
                          ;; The URL the clone used rides with the remote for
                          ;; persist and restore (`workspace-exec-fn`).
                          remote   (some-> (git-remote env-config)
                                           (cond-> clone-url (assoc :clone-url clone-url)))]
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
                    (do (without-interrupt #(remove-container descriptor container-name))
                        bootstrap-result)))
                create-result)))))))

  (execute! [_this environment-id command opts]
    (try
      (exec-in-container descriptor environment-id command
                         (with-git-credentials
                           opts (delay (remote-credential-env descriptor git-remotes environment-id))))
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
    (let [cred-env (delay (remote-credential-env descriptor git-remotes environment-id))
          exec!    (workspace-exec-fn descriptor environment-id
                                      (get opts :workdir default-workdir) cred-env
                                      (some-> git-remotes deref (get environment-id)))]
      (workspace/git-persist! exec! (workspace-opts opts git-remotes environment-id))))

  (restore-workspace! [_this environment-id opts]
    (let [cred-env (delay (remote-credential-env descriptor git-remotes environment-id))
          exec!    (workspace-exec-fn descriptor environment-id
                                      (get opts :workdir default-workdir) cred-env
                                      (some-> git-remotes deref (get environment-id)))]
      (workspace/git-restore! exec! (workspace-opts opts git-remotes environment-id)))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Factory
;; ============================================================================
(defn ^{:stratum 1} create-oci-cli-executor
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-docker-executor
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
