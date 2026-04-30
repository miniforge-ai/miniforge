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
   executor. Per-kind data lives in `runtime/registry.edn`; this namespace
   owns construction, the boundary schema, and the runtime probe.

   Per N11-delta §2:
     {:runtime/kind         keyword     ; :docker | :podman | :nerdctl
      :runtime/executable   string      ; resolved CLI path
      :runtime/version      string      ; reported by `<exe> --version`
      :runtime/rootless?    boolean
      :runtime/capabilities #{keyword}}"
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.protocols.impl.runtime.registry :as registry]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Boundary schema

(def DescriptorSchema
  "Schema for a runtime descriptor — the parameterization of the OCI-CLI
   executor. Validated at the descriptor-construction boundary; consumers
   inside the component trust the shape."
  [:map
   [:runtime/kind         :keyword]
   [:runtime/executable   :string]
   [:runtime/version      [:maybe :string]]
   [:runtime/rootless?    :boolean]
   [:runtime/capabilities [:set :keyword]]])

;------------------------------------------------------------------------------ Layer 1
;; Error factories

(defn- unknown-kind-error
  "Return an ex-info anomaly for an unrecognized runtime kind."
  [kind]
  (ex-info (messages/t :descriptor/unknown-kind
                       {:kind  kind
                        :known (registry/known-kinds)})
           {:runtime/unknown-kind kind
            :runtime/known-kinds  (registry/known-kinds)}))

(defn- unsupported-kind-error
  "Return an ex-info anomaly for a known-but-not-yet-supported runtime kind."
  [kind]
  (ex-info (messages/t :descriptor/unsupported-kind {:kind kind})
           {:runtime/unsupported     kind
            :runtime/supported-kinds (registry/supported-kinds)}))

;------------------------------------------------------------------------------ Layer 2
;; Resolvers

(defn- resolve-executable
  "Pick the CLI binary path for a descriptor.

   Priority:
   1. Explicit `:executable` on the config map.
   2. Legacy `:docker-path` alias (only when constructing a :docker descriptor).
   3. Registry default for the kind."
  [kind config]
  (or (:executable config)
      (when (= kind :docker) (:docker-path config))
      (registry/executable kind)))

;------------------------------------------------------------------------------ Layer 3
;; Construction

(defn make-descriptor
  "Build a runtime descriptor from a config map.

   Config keys (all optional):
     :runtime-kind   - one of registry's known kinds; default :docker
     :executable     - explicit CLI path; defaults to the kind's binary name
                       (resolved via PATH at invocation time)
     :docker-path    - legacy alias for :executable when :runtime-kind is
                       :docker (preserves the pre-N11-delta config shape)

   Throws ex-info with :runtime/unsupported when called with a known but
   not-yet-implemented kind (e.g. :podman in Phase 1). Throws with
   :runtime/unknown-kind for anything outside the registry."
  [config]
  (let [kind (get config :runtime-kind :docker)]
    (when-not (registry/known? kind)
      (throw (unknown-kind-error kind)))
    (when-not (registry/supported? kind)
      (throw (unsupported-kind-error kind)))
    (let [exe          (resolve-executable kind config)
          capabilities (registry/capabilities kind)
          descriptor   {:runtime/kind         kind
                        :runtime/executable   exe
                        :runtime/version      nil
                        :runtime/rootless?    false
                        :runtime/capabilities capabilities}]
      (when-not (m/validate DescriptorSchema descriptor)
        (throw (ex-info "Constructed descriptor failed schema validation"
                        {:runtime/invalid-descriptor descriptor})))
      descriptor)))

;------------------------------------------------------------------------------ Layer 4
;; Accessors

(def ^:private default-executable-fallback
  "Used only when callers pass a nil descriptor (legacy nil-path back-compat).
   Real descriptors always carry an explicit executable."
  "docker")

(defn kind
  "Return the runtime kind keyword (:docker, :podman, ...)."
  [descriptor]
  (:runtime/kind descriptor))

(defn executable
  "Return the resolved CLI binary path/name for a descriptor."
  [descriptor]
  (get descriptor :runtime/executable default-executable-fallback))

(defn capabilities
  "Return the descriptor's capability set."
  [descriptor]
  (get descriptor :runtime/capabilities #{}))

(defn capable?
  "True when the descriptor advertises capability `cap`."
  [descriptor cap]
  (contains? (capabilities descriptor) cap))

;------------------------------------------------------------------------------ Layer 5
;; Probe

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

   Reads the `<exe> info --format` template from the registry so a future
   runtime that needs a different probe template just adds an entry."
  [descriptor]
  (let [runtime-kind (kind descriptor)
        exe          (executable descriptor)
        template     (registry/flag runtime-kind :info-format-template)
        result       (run-probe exe template)]
    (if (zero? (:exit result))
      {:available?      true
       :runtime-version (str/trim (:out result))}
      {:available? false
       :reason     (:err result)})))
