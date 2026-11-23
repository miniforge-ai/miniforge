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
(ns ai.miniforge.llm.protocols.impl.llm-client.settings
  "Where the client's settings come from: the `llm/client-defaults.edn`
   and `llm/backends.edn` resources, and environment variables
   (`getenv-value`, the indirection tests redefine, and the on/off
   switches read through it)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} load-client-defaults
  []
  (if-let [resource (io/resource "llm/client-defaults.edn")]
    (edn/read-string (slurp resource))
    (response/throw-anomaly! :anomalies/not-found
                             "Missing llm/client-defaults.edn resource")))

;; Pure-data backend fields (`:cmd`, endpoints, `:default-model`,
;; `:models`, etc.) live in `llm/backends.edn`; the function-valued
;; fields (`:stream-parser`, `:args-fn`) live in `backend-registry`
;; because they reference the parser and arg-builder fns. The two are
;; merged per backend there to form `backends`.
(defn- ^{:stratum 0} load-backend-data
  []
  (if-let [resource (io/resource "llm/backends.edn")]
    (edn/read-string (slurp resource))
    (response/throw-anomaly! :anomalies/not-found
                             "Missing llm/backends.edn resource")))

(defn ^{:stratum 0} getenv-value
  "Indirection over `System/getenv` so env-dependent backend resolution
   (api-key, base-url) is testable via `with-redefs` on this var
   (`settings/getenv-value`; the `llm-client` alias does not reach callers
   in the sub-namespaces). Kept public like `http-backend/http-post-request`
   for the same reason."
  [k]
  (System/getenv k))

(def ^{:stratum 0} ^:private network-monitor-env-var
  "Environment variable that switches the network-drop monitor off for
   streaming CLI calls, for a network where the provider's probe
   endpoint is unreachable although the agent CLI works (for example a
   gateway set through the CLI's own base-URL setting)."
  "MINIFORGE_NETWORK_MONITOR")

(def ^{:stratum 0} ^:private env-flag-off-values
  "Values (trimmed, any case) that read as off for an on/off environment
   variable: `network-monitor-env-var` and the provider switches in a
   backend's `:probe-endpoint-routes`."
  #{"off" "false" "0" "no"})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} env-flag-off?
  "True when `value` is one of `env-flag-off-values`."
  [value]
  (contains? env-flag-off-values
             (some-> value str/trim str/lower-case)))

(def ^{:stratum 1} ^:private client-defaults
  (delay (load-client-defaults)))

(def ^{:stratum 1} backend-data
  (load-backend-data))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} network-monitor-enabled?
  "False when `network-monitor-env-var` reads as off."
  []
  (not (env-flag-off? (getenv-value network-monitor-env-var))))

(defn ^{:stratum 2} client-default
  [path]
  (get-in @client-defaults path))
