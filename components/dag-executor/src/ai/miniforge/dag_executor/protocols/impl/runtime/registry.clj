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

(def registry-resource-path
  "Classpath resource path for the runtime registry EDN."
  "config/dag-executor/runtime/registry.edn")

(defn- read-edn-resource
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (-> resource slurp edn/read-string)))

(def registry
  "Per-kind runtime data, loaded once at namespace init."
  (delay (read-edn-resource registry-resource-path)))

(defn entry
  "Return the registry entry for runtime kind `k`, or nil when unknown."
  [k]
  (get @registry k))

(defn known-kinds
  "Set of all runtime kinds the registry knows about (supported or not)."
  []
  (set (keys @registry)))

(defn supported-kinds
  "Set of runtime kinds the executor can construct a working descriptor for."
  []
  (->> @registry
       (filter (fn [[_ v]] (:supported? v)))
       (map key)
       set))

(defn supported?
  "True when the registry has `k` and marks it supported."
  [k]
  (boolean (some-> (entry k) :supported?)))

(defn known?
  "True when the registry has any entry for `k`."
  [k]
  (some? (entry k)))

(defn executable
  "Default CLI binary name for runtime kind `k`, or nil when unknown."
  [k]
  (some-> (entry k) :executable))

(defn capabilities
  "Capability set for runtime kind `k`. Empty set when unknown."
  [k]
  (or (some-> (entry k) :capabilities) #{}))

(defn flag
  "Look up dialect entry `flag-key` for runtime kind `k`. Falls back to the
   Docker dialect when `k` has no entry for `flag-key`, since the OCI-CLI
   surface is Docker-shaped by convention; runtimes that diverge declare an
   override in their own dialect map."
  [k flag-key]
  (or (get-in (entry k) [:flags flag-key])
      (get-in (entry :docker) [:flags flag-key])))

(defn default
  "Look up per-runtime container default `default-key` for kind `k`.
   Falls back to the Docker default when `k` has no override; this lets
   future kinds inherit Docker's defaults until they declare their own."
  [k default-key]
  (or (get-in (entry k) [:defaults default-key])
      (get-in (entry :docker) [:defaults default-key])))

(defn user-spec
  "Return the `<uid>:<gid>` string for runtime kind `k`, derived from the
   numeric :uid and :gid defaults so the user spec and tmpfs uid=/gid=
   options stay in lockstep."
  [k]
  (str (default k :uid) ":" (default k :gid)))

(defn tmpfs-mount-options
  "Build the comma-separated options string appended to `--tmpfs <path>:`
   from the runtime's defaults. Uses the runtime's :uid / :gid / :tmpfs-size
   so changing one of those defaults flows through the user spec and the
   tmpfs options together."
  [k]
  (let [uid  (default k :uid)
        gid  (default k :gid)
        size (default k :tmpfs-size)]
    (str "rw,nosuid,nodev,exec,size=" size ",uid=" uid ",gid=" gid)))
