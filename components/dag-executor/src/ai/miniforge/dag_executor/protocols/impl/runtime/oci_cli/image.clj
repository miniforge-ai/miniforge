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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.image
  "Task-runner images for the OCI-CLI executor: digests, existence, pull and
   build."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.images :as images]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.env
    :refer [env-inspect-format parse-inspected-env]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process
    :refer [run-runtime]]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.java.io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;; Resource paths for Dockerfiles (for documentation/building images).
;; The Dockerfile syntax is OCI-standard; "docker/" in the resource path is
;; historical and stays unchanged in Phase 1.
(def ^{:stratum 0} dockerfile-resources
  {:minimal "executor/docker/Dockerfile.task-runner"
   :clojure "executor/docker/Dockerfile.task-runner-clojure"})

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
   Returns absolute path to the Dockerfile. `build-image!` sends the
   Dockerfile's directory as the build context, so a Dockerfile inside a JAR
   is extracted into a fresh directory of its own: the system temp directory
   would send every file in it (other processes' files, env files) to the
   runtime."
  [resource-path]
  (let [resource (clojure.java.io/resource resource-path)]
    (cond
      ;; Direct file on classpath
      (and resource (= "file" (.getScheme (.toURI resource))))
      (.getAbsolutePath (clojure.java.io/file (.toURI resource)))

      ;; Resource in JAR — extract into an otherwise empty temp directory
      resource
      (let [dir       (.toFile (java.nio.file.Files/createTempDirectory
                                "miniforge-dockerfile-"
                                (make-array java.nio.file.attribute.FileAttribute 0)))
            temp-file (clojure.java.io/file dir "Dockerfile")]
        ;; deleteOnExit runs in reverse order: the file, then its directory.
        (.deleteOnExit dir)
        (.deleteOnExit temp-file)
        (with-open [in (clojure.java.io/input-stream resource)]
          (clojure.java.io/copy in temp-file))
        (.getAbsolutePath temp-file))

      ;; Try as direct filesystem path
      :else
      (let [f (clojure.java.io/file resource-path)]
        (when (.exists f)
          (.getAbsolutePath f))))))

(defn ^{:stratum 0} image-env
  "The ENV of local image `image` as {name value} (`parse-inspected-env`),
   or nil on failure."
  [descriptor image]
  (try+
    (let [result (run-runtime descriptor "image" "inspect" image "--format" env-inspect-format)]
      (when (zero? (:exit result))
        (parse-inspected-env (:out result))))
    (catch Exception _ nil)))

(defn ^{:stratum 0} image-exists?
  "Check if a container image exists locally."
  [descriptor image]
  (let [result (run-runtime descriptor "image" "inspect" image)]
    (zero? (:exit result))))

(defn ^{:stratum 0} image-repo-digest
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

(defn ^{:stratum 0} pull-image!
  "Pull `image` via the runtime. Best-effort: a failed pull (e.g. a
   locally-built image with no registry) leaves the image absent, which the
   caller surfaces as a nil digest."
  [descriptor image]
  (try (run-runtime descriptor "pull" image) (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} container-image-digest
  "Return the SHA256 image digest for a container, or nil on failure."
  [descriptor container-name]
  (try
    (let [inspect-result (run-runtime descriptor
                                      "inspect" container-name
                                      "--format" "{{.Image}}")]
      (when (zero? (:exit inspect-result))
        (normalize-image-id (:out inspect-result))))
    (catch Exception _ nil)))

(defn ^{:stratum 1} image-config-digest
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

(defn ^{:stratum 1} build-image!
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-image-digest!
  "Resolve the pinned `sha256:<64hex>` digest for `image`, pulling it first when
   it is not present locally so `image inspect` can read it (inspect does not
   pull). Returns nil only when the image cannot be resolved even after a pull —
   a genuinely unpinnable image, which the security gate then blocks. This is
   the production digest resolver injected into `security-gate-check`."
  [descriptor image]
  (when-not (image-exists? descriptor image)
    (pull-image! descriptor image))
  (image-config-digest descriptor image))

(defn ^{:stratum 2} ensure-image!
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
