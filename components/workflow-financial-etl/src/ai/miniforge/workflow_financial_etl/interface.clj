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

(ns ai.miniforge.workflow-financial-etl.interface
  "Public API for ETL product-owned execution helpers."
  (:require
   [ai.miniforge.workflow-financial-etl.core :as core]
   [ai.miniforge.workflow-financial-etl.events :as events]))

(def create-etl-state core/create-etl-state)
(def classify-sources core/classify-sources)
(def scan-sources core/scan-sources)
(def extract-knowledge core/extract-knowledge)
(def validate-packs core/validate-packs)
(def run-etl-workflow core/run-etl-workflow)

(def etl-completed-event events/etl-completed-event)
(def etl-failed-event events/etl-failed-event)
(def emit-etl-completed events/emit-etl-completed)
(def emit-etl-failed events/emit-etl-failed)
