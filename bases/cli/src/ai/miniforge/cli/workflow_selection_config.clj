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
(ns ai.miniforge.cli.workflow-selection-config
  "Resource-driven workflow selection profile resolution."
  (:require
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0

;; Resource loading
(def ^{:stratum 0} selection-profiles-resource
  "Classpath resource path for workflow selection profile mappings."
  "config/workflow/selection-profiles.edn")

(defn- ^{:stratum 0} read-selection-profile-config
  "Read a single selection profile config resource."
  [resource]
  (let [config (-> resource slurp edn/read-string)]
    (get config :workflow-selection/profiles {})))

;; Generic fallback resolution
(defn- ^{:stratum 0} workflow-characteristics
  "Resolve workflow characteristics through the workflow interface."
  [workflow-def]
  (workflow/workflow-characteristics workflow-def))

(defn- ^{:stratum 0} available-workflow-definitions
  "Return full workflow definitions from the registry for fallback scoring."
  []
  (workflow/ensure-initialized!)
  (keep workflow/get-workflow (workflow/list-workflow-ids)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configured-selection-profiles
  "Merge workflow selection profile mappings from all matching classpath resources."
  []
  (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader)
                                       selection-profiles-resource))
       (map read-selection-profile-config)
       (apply merge {})))

(defn- ^{:stratum 1} simplest-workflow-id
  "Choose the simplest available workflow by phase count and max iterations."
  [available-workflows]
  (->> available-workflows
       (sort-by (fn [workflow]
                  (let [{:keys [phases max-iterations]} (workflow-characteristics workflow)]
                    [phases max-iterations])))
       first
       :workflow/id))

(defn- ^{:stratum 1} most-comprehensive-workflow-id
  "Choose the most comprehensive available workflow by phase count."
  [available-workflows]
  (->> available-workflows
       (sort-by (fn [workflow]
                  (let [{:keys [phases max-iterations]} (workflow-characteristics workflow)]
                    [(- phases) (- max-iterations)])))
       first
       :workflow/id))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} resolve-profile-fallback
  "Resolve a profile via generic workflow characteristics when no config is present."
  [profile available-workflows]
  (case profile
    :comprehensive (most-comprehensive-workflow-id available-workflows)
    :fast (simplest-workflow-id available-workflows)
    :default (or (resolve-profile-fallback :fast available-workflows)
                 (most-comprehensive-workflow-id available-workflows))
    nil))

;------------------------------------------------------------------------------ Layer 3

;; Public API
(defn ^{:stratum 3} resolve-selection-profile
  "Resolve a logical selection profile to a concrete workflow id.

   Profiles are app-owned configuration. If a configured profile points at a
   workflow not present on the active classpath, fall back to generic workflow
  characteristics."
  ([profile]
   (let [available-workflows (available-workflow-definitions)]
     (resolve-selection-profile profile available-workflows)))
  ([profile available-workflows]
   (let [configured-id (get (configured-selection-profiles) profile)
         available-ids (set (map :workflow/id available-workflows))]
     (cond
       (contains? available-ids configured-id) configured-id
       :else (resolve-profile-fallback profile available-workflows)))))
