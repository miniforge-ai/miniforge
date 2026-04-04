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

(ns ai.miniforge.cli.workflow-recommendation-config
  "Resource-driven workflow recommendation prompt configuration."
  (:require
   [ai.miniforge.cli.resource-config :as resource-config]))

;------------------------------------------------------------------------------ Layer 0
;; Resource loading

(def recommendation-config-resource
  "Classpath resource path for workflow recommendation prompt config."
  "config/workflow/recommendation-prompt.edn")

(def default-recommendation-config-resource
  "Classpath resource path for base workflow recommendation prompt config."
  "config/workflow/recommendation-prompt-default.edn")

(defn default-prompt-config
  "Load the base workflow recommendation prompt config from resources."
  []
  (resource-config/merged-resource-config default-recommendation-config-resource
                                          :workflow-recommendation/prompt
                                          {}))

(defn- merge-prompt-config
  "Merge prompt config maps, preserving nested summary labels."
  [base override]
  (-> base
      (merge override)
      (update :summary-labels #(merge (:summary-labels base) %))))

(defn recommendation-prompt-config
  "Resolve the active workflow recommendation prompt config from the classpath."
  []
  (merge-prompt-config (default-prompt-config)
                       (resource-config/merged-resource-config recommendation-config-resource
                                                               :workflow-recommendation/prompt
                                                               {})))
