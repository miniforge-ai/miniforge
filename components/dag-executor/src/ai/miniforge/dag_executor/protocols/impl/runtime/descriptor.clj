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

(ns ai.miniforge.dag-executor.protocols.impl.runtime.descriptor
  "Runtime descriptor for the OCI-CLI executor.

   A descriptor fully specifies the local container runtime used by the
   executor. Phase 1 supports only :docker. Phase 2 adds :podman.

   Per N11-delta §2:
     {:runtime/kind         keyword     ; :docker | :podman | :nerdctl
      :runtime/executable   string      ; resolved CLI path
      :runtime/version      string      ; reported by `<exe> --version`
      :runtime/rootless?    boolean
      :runtime/capabilities #{keyword}}"
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.flags :as flags]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;; ============================================================================
;; Supported runtime kinds (Phase 1)
;; ============================================================================

(def supported-kinds
  "Runtime kinds the executor supports today.
   Phase 1 ships :docker only. Phase 2 adds :podman."
  #{:docker})

(def known-kinds
  "Runtime kinds the executor knows how to construct a descriptor for, even
   if not yet runnable. Used to give a helpful error for :podman before
   Phase 2 lands."
  #{:docker :podman :nerdctl})

;; ============================================================================
;; Default capability sets per kind
;; ============================================================================

(def docker-capabilities
  "Capabilities advertised by the Docker runtime per N11-delta §4.
   These are what we rely on in the OCI-CLI executor; runtime probing
   refines them at startup."
  #{:oci-images
    :run :exec :build
    :bind-mounts :tmpfs-mounts
    :env-vars :working-dir :user-mapping
    :resource-limits
    :network-modes/none :network-modes/bridge
    :image-digest-pinning
    :graceful-stop
    :no-new-privileges :read-only-root :cap-drop-all
    :tmpfs-uid-gid-options})

(defn- default-capabilities
  [kind]
  (case kind
    :docker docker-capabilities
    ;; Other kinds: empty until Phase 2 introduces them.
    #{}))

(defn- default-executable
  [kind]
  (case kind
    :docker  "docker"
    :podman  "podman"
    :nerdctl "nerdctl"
    nil))

;; ============================================================================
;; Descriptor construction
;; ============================================================================

(defn make-descriptor
  "Build a runtime descriptor from a config map.

   Config keys (all optional unless noted):
     :runtime-kind   - one of #{:docker :podman :nerdctl}; default :docker
     :executable     - explicit CLI path; defaults to the kind's binary name
                       (resolved via PATH at invocation time)
     :docker-path    - legacy alias for :executable when :runtime-kind is
                       :docker (preserves the pre-N11-delta config shape)

   Throws ex-info with :runtime/unsupported when called with a known but
   not-yet-implemented kind (e.g. :podman in Phase 1). Throws with
   :runtime/unknown-kind for anything outside known-kinds."
  [config]
  (let [kind (get config :runtime-kind :docker)]
    (cond
      (not (contains? known-kinds kind))
      (throw (ex-info (str "Unknown runtime kind: " kind)
                      {:runtime/unknown-kind kind
                       :runtime/known-kinds known-kinds}))

      (not (contains? supported-kinds kind))
      (throw (ex-info (str "Runtime kind " kind " is not yet supported. "
                           "Phase 1 of N11-delta ships :docker only; "
                           ":podman lands in Phase 2.")
                      {:runtime/unsupported kind
                       :runtime/supported-kinds supported-kinds}))

      :else
      {:runtime/kind         kind
       :runtime/executable   (or (:executable config)
                                 (when (= kind :docker) (:docker-path config))
                                 (default-executable kind))
       :runtime/version      nil
       :runtime/rootless?    false
       :runtime/capabilities (default-capabilities kind)})))

(defn executable
  "Return the resolved CLI binary path/name for a descriptor."
  [descriptor]
  (or (:runtime/executable descriptor) "docker"))

(defn kind
  "Return the runtime kind keyword (:docker, :podman, ...)."
  [descriptor]
  (:runtime/kind descriptor))

(defn capabilities
  "Return the descriptor's capability set."
  [descriptor]
  (or (:runtime/capabilities descriptor) #{}))

(defn capable?
  "True when the descriptor advertises capability `cap`."
  [descriptor cap]
  (contains? (capabilities descriptor) cap))

;; ============================================================================
;; Probe
;; ============================================================================

(defn- run-probe
  "Invoke `<exe> info --format <template>` and return the shell result.
   Wrapped so the executor can mock this in tests."
  [exe template]
  (try
    (shell/sh exe "info" "--format" template)
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn runtime-info
  "Probe the runtime for availability and version.

   Returns {:available? true :runtime-version <string>} on success,
   {:available? false :reason <string>} on failure.

   Consults runtime/flags for the per-kind `<exe> info --format` template
   so Phase 2 can override the Docker default for Podman without touching
   this function."
  [descriptor]
  (let [exe      (executable descriptor)
        template (flags/flag (kind descriptor) :info-format-template)
        result   (run-probe exe template)]
    (if (zero? (:exit result))
      {:available?      true
       :runtime-version (str/trim (:out result))}
      {:available? false
       :reason     (:err result)})))
