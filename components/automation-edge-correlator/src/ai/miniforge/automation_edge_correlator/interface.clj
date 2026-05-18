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

(ns ai.miniforge.automation-edge-correlator.interface
  "Public API for the automation-edge-correlator component.

   N15-1 exposes schema and trigger-classification helpers only.
   Lifecycle (`start!` / `stop!` / `attach!`) lands in N15-3 once
   `core.clj` and `emitter.clj` are in place."
  (:require
   [ai.miniforge.automation-edge-correlator.schema :as schema]
   [ai.miniforge.automation-edge-correlator.triggers :as triggers]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def AutomationEdge          schema/AutomationEdge)
(def routing-trigger-kinds   schema/routing-trigger-kinds)
(def automation-edge-statuses schema/automation-edge-statuses)
(def registry                schema/registry)

;------------------------------------------------------------------------------ Layer 0
;; Trigger classification

(def classify-trigger triggers/classify-trigger)
