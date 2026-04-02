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

(ns ai.miniforge.phase.deploy.defaults
  "Load deployment phase defaults from EDN and register them with the phase registry."
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.schema.interface :as schema]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Config loading + validation

(def ^:private config-path
  "Classpath resource holding deployment phase defaults."
  "config/phase/deploy-defaults.edn")

(def DeployBudget
  [:map
   [:tokens {:optional true} nat-int?]
   [:iterations {:optional true} pos-int?]
   [:time-seconds {:optional true} pos-int?]])

(def DeployPhaseDefaults
  [:map
   [:agent {:optional true} [:maybe keyword?]]
   [:gates {:optional true} [:vector keyword?]]
   [:budget {:optional true} DeployBudget]
   [:stack-dir {:optional true} :string]
   [:stack {:optional true} :string]
   [:gcp-project {:optional true} :string]])

(def DeployDefaultsConfig
  [:map
   [:provision {:optional true} DeployPhaseDefaults]
   [:deploy {:optional true} DeployPhaseDefaults]
   [:validate {:optional true} DeployPhaseDefaults]])

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn- load-defaults-config
  []
  (if-let [resource (io/resource config-path)]
    (->> resource
         slurp
         edn/read-string
         (validate! DeployDefaultsConfig))
    {}))

(def ^:private defaults-config
  (delay (load-defaults-config)))

;------------------------------------------------------------------------------ Layer 1
;; Registry integration

(defn register-defaults!
  "Register all deployment phase defaults with the shared phase registry."
  []
  (doseq [[phase-kw defaults] @defaults-config]
    (registry/register-phase-defaults! phase-kw defaults)))

(defn phase-defaults
  "Get the defaults for a deployment phase keyword."
  [phase-kw]
  (get @defaults-config phase-kw {}))

(register-defaults!)
