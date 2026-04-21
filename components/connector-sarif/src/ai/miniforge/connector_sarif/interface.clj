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

(ns ai.miniforge.connector-sarif.interface
  "Public API for the SARIF/CSV connector.
   A SourceConnector that extracts violations from SARIF v2.1.0 JSON
   and CSV scan output files."
  (:require [ai.miniforge.connector-sarif.core :as core]
            [ai.miniforge.connector-sarif.schema :as schema]
            [ai.miniforge.connector.interface :as conn]))

(defn create-sarif-connector
  "Create a new SarifConnector instance."
  []
  (core/->SarifConnector))

(def connector-metadata
  "Registration metadata for the SARIF connector."
  {:connector/name         "SARIF/CSV Scanner Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch}
   :connector/auth-methods #{:none}
   :connector/retry-policy (conn/retry-policy :none)
   :connector/maintainer   "data-foundry"})

;; Schema exports
(def SarifViolation schema/SarifViolation)
(def SarifConfig schema/SarifConfig)

(defn validate-config [config] (schema/validate-config config))
(defn validate-violation [v] (schema/validate-violation v))
(defn violation-json-schema [] (schema/violation-json-schema))
(defn config-json-schema [] (schema/config-json-schema))
