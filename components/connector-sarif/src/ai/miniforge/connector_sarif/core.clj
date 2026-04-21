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

(ns ai.miniforge.connector-sarif.core
  "SarifConnector defrecord — thin protocol wrapper delegating to impl."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-sarif.impl :as impl]))

(defrecord SarifConnector []
  connector/Connector
  (connect [_ config _auth] (impl/do-connect config))
  (close   [_ handle]       (impl/do-close handle))

  connector/SourceConnector
  (discover    [_ handle opts]                       (impl/do-discover handle opts))
  (extract     [_ handle schema-name opts]           (impl/do-extract handle schema-name opts))
  (checkpoint  [_ handle connector-id cursor-state]  (impl/do-checkpoint handle connector-id cursor-state)))
