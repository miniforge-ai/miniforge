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
(ns ai.miniforge.cli.workflow-selection-config.profiles-resource
  "Classpath resource loading and merging for workflow selection profile
   mappings. Split out of `ai.miniforge.cli.workflow-selection-config` (rule
   210: the parent namespace measured 4 real layers, max 3; this
   resource-loading concern is layer-coherent on its own)."
  (:require
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} configured-selection-profiles
  "Merge workflow selection profile mappings from all matching classpath resources."
  []
  (->> (enumeration-seq (.getResources (clojure.lang.RT/baseLoader)
                                       selection-profiles-resource))
       (map read-selection-profile-config)
       (apply merge {})))
