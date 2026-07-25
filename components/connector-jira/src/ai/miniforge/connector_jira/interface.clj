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
(ns ai.miniforge.connector-jira.interface
  "Public API for the Jira Cloud REST API connector component."
  (:require [ai.miniforge.connector-jira.core :as core]
            [ai.miniforge.connector-jira.schema :as schema]
            [ai.miniforge.connector.interface :as conn]))

;------------------------------------------------------------------------------ Layer 0

;; Schemas (exported for consumers)
(def ^{:stratum 0} JiraConfig
  "Malli schema for Jira connector configuration."
  schema/JiraConfig)

;; Factory and metadata
(defn ^{:stratum 0} create-jira-connector
  "Create a new JiraConnector instance."
  []
  (core/->JiraConnector))

(def ^{:stratum 0} connector-metadata
  "Registration metadata for the Jira Cloud REST API connector."
  {:connector/name         "Jira Cloud REST API Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/discovery :cap/incremental :cap/pagination}
   :connector/auth-methods #{:basic}
   :connector/retry-policy (conn/retry-policy :default)
   :connector/maintainer   "data-foundry"})
