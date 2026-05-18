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
;;
;; `schema/registry` is intentionally NOT re-exported: it is an internal
;; implementation detail that inlines primitive keyword types to avoid a
;; cross-boundary reach into `ai.miniforge.schema.core/registry`. Exposing it
;; would invite downstream callers to depend on its exact key set, turning
;; later consolidation into a breaking change.

(def AutomationEdge          schema/AutomationEdge)
(def routing-trigger-kinds   schema/routing-trigger-kinds)
(def automation-edge-statuses schema/automation-edge-statuses)

;------------------------------------------------------------------------------ Layer 0
;; Trigger classification

(def classify-trigger triggers/classify-trigger)
