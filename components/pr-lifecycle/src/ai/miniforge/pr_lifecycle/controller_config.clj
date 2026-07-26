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
(ns ai.miniforge.pr-lifecycle.controller-config
  "Load PR lifecycle controller defaults from EDN configuration."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

;; Schemas + config loading
(def ^{:stratum 0} ControllerDefaults
  [:map
   [:max-fix-iterations nat-int?]
   [:ci-poll-interval-ms nat-int?]
   [:review-poll-interval-ms nat-int?]
   [:auto-resolve-comments boolean?]
   [:branch-name-prefix :string]])

(def ^{:stratum 0} ^:private controller-defaults-resource
  "Classpath resource holding controller defaults."
  "config/pr-lifecycle/controller-defaults.edn")

(defn- ^{:stratum 0} validate!
  [result-schema value]
  (schema/validate result-schema value))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ControllerConfig
  [:map
   [:pr-lifecycle/controller-defaults ControllerDefaults]])

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} load-controller-config
  []
  (validate! ControllerConfig
             (config/load-config-resource controller-defaults-resource
                                          [:pr-lifecycle/controller-defaults])))

;------------------------------------------------------------------------------ Layer 3

(def ^{:stratum 3} ^:private controller-config
  (delay (load-controller-config)))

;------------------------------------------------------------------------------ Layer 4

;; Public helpers
(defn ^{:stratum 4} controller-defaults
  "Return the PR lifecycle controller defaults map."
  []
  (:pr-lifecycle/controller-defaults @controller-config))
