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

(ns ai.miniforge.connector-file.interface
  "Public API for the file connector component."
  (:require [ai.miniforge.connector-file.core :as core]
            [ai.miniforge.connector.interface :as conn]))

(defn create-file-connector
  "Create a new FileConnector instance."
  []
  (core/->FileConnector))

(def connector-metadata
  "Registration metadata for the file connector."
  {:connector/name         "File Connector"
   :connector/type         :bidirectional
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/batch}
   :connector/auth-methods #{:none}
   :connector/retry-policy (conn/retry-policy :none)
   :connector/maintainer   "data-foundry"})
