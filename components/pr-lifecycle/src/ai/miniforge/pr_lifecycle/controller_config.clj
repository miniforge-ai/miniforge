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
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [ai.miniforge.schema.interface :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas + config loading

(def ControllerDefaults
  [:map
   [:max-fix-iterations nat-int?]
   [:ci-poll-interval-ms nat-int?]
   [:review-poll-interval-ms nat-int?]
   [:auto-resolve-comments boolean?]
   [:branch-name-prefix :string]])

(def ControllerConfig
  [:map
   [:pr-lifecycle/controller-defaults ControllerDefaults]])

(def ^:private controller-defaults-resource
  "Classpath resource holding controller defaults."
  "config/pr-lifecycle/controller-defaults.edn")

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn- load-controller-config
  []
  (if-let [resource (io/resource controller-defaults-resource)]
    (->> resource slurp edn/read-string (validate! ControllerConfig))
    (throw (ex-info (messages/t :config/missing-resource
                                {:resource controller-defaults-resource})
                    {:hint (messages/t :config/classpath-hint)}))))

(def ^:private controller-config
  (delay (load-controller-config)))

;------------------------------------------------------------------------------ Layer 1
;; Public helpers

(defn controller-defaults
  "Return the PR lifecycle controller defaults map."
  []
  (:pr-lifecycle/controller-defaults @controller-config))
