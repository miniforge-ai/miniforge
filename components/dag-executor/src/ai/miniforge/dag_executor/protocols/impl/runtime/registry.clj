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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.registry
  "Loads the runtime registry from
   `config/dag-executor/runtime/registry.edn` and exposes lookups for
   per-kind data: executable name, supported flag, capability set, and
   CLI flag dialect.

   The registry is configuration, not code — adding a runtime (or marking
   one supported) is an EDN edit, not a code change."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} registry-resource-path
  "Classpath resource path for the runtime registry EDN."
  "config/dag-executor/runtime/registry.edn")

(defn- ^{:stratum 0} read-edn-resource
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (-> resource slurp edn/read-string)))

(def ^{:stratum 0} ^:private world-writable-sticky-mode
  "tmpfs mode for scratch mounts on kinds without :tmpfs-uid-gid-options:
   1777 = world-writable with the sticky bit (/tmp semantics), so the
   non-root container user can write without uid=/gid= ownership options."
  "1777")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} registry
  "Per-kind runtime data, loaded once at namespace init."
  (delay (read-edn-resource registry-resource-path)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} entry
  "Return the registry entry for runtime kind `k`, or nil when unknown."
  [k]
  (get @registry k))

(defn ^{:stratum 2} known-kinds
  "Set of all runtime kinds the registry knows about (supported or not)."
  []
  (set (keys @registry)))

(defn ^{:stratum 2} supported-kinds
  "Set of runtime kinds the executor can construct a working descriptor for."
  []
  (->> @registry
       (filter (fn [[_ v]] (:supported? v)))
       (map key)
       set))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} supported?
  "True when the registry has `k` and marks it supported."
  [k]
  (boolean (some-> (entry k) :supported?)))

(defn ^{:stratum 3} known?
  "True when the registry has any entry for `k`."
  [k]
  (some? (entry k)))

(defn ^{:stratum 3} executable
  "Default CLI binary name for runtime kind `k`, or nil when unknown."
  [k]
  (some-> (entry k) :executable))

(defn ^{:stratum 3} capabilities
  "Capability set for runtime kind `k`. Empty set when unknown."
  [k]
  (or (some-> (entry k) :capabilities) #{}))

(defn ^{:stratum 3} flag
  "Look up dialect entry `flag-key` for runtime kind `k`. Falls back to the
   Docker dialect when `k` has no entry for `flag-key`, since the OCI-CLI
   surface is Docker-shaped by convention; runtimes that diverge declare an
   override in their own dialect map."
  [k flag-key]
  (or (get-in (entry k) [:flags flag-key])
      (get-in (entry :docker) [:flags flag-key])))

(defn ^{:stratum 3} default
  "Look up per-runtime container default `default-key` for kind `k`.
   Falls back to the Docker default when `k` has no override; this lets
   future kinds inherit Docker's defaults until they declare their own."
  [k default-key]
  (or (get-in (entry k) [:defaults default-key])
      (get-in (entry :docker) [:defaults default-key])))

(defn ^{:stratum 3} cli-env-names
  "Set of env var names the CLI for runtime kind `k` reads from its own
   process environment. Falls back to Docker's set, like `default`."
  [k]
  (or (get (entry k) :cli-env-names)
      (get (entry :docker) :cli-env-names #{})))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} user-spec
  "Return the `<uid>:<gid>` string for runtime kind `k`, derived from the
   numeric :uid and :gid defaults so the user spec and tmpfs uid=/gid=
   options stay in lockstep."
  [k]
  (str (default k :uid) ":" (default k :gid)))

(defn ^{:stratum 4} tmpfs-mount-options
  "Build the comma-separated options string appended to `--tmpfs <path>:`
   from the runtime's defaults. Uses the runtime's :uid / :gid / :tmpfs-size
   so changing one of those defaults flows through the user spec and the
   tmpfs options together.

   uid=/gid= are emitted only for kinds advertising the
   :tmpfs-uid-gid-options capability. Kinds without it (Podman rejects
   those options as unknown) get mode=1777 instead, so the non-root
   container user can still write to the scratch mounts."
  [k]
  (let [size (default k :tmpfs-size)
        base (str "rw,nosuid,nodev,exec,size=" size)]
    (if (contains? (capabilities k) :tmpfs-uid-gid-options)
      (str base ",uid=" (default k :uid) ",gid=" (default k :gid))
      (str base ",mode=" world-writable-sticky-mode))))
