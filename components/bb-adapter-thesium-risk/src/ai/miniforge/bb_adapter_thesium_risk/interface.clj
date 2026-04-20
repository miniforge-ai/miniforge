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

(ns ai.miniforge.bb-adapter-thesium-risk.interface
  "Per-repo adapter for thesium-risk (risk-dashboard) bb tasks.

   Composes bb-utils primitives (bb-r2, bb-data-plane-http,
   bb-out/paths/proc) into the orchestration flows that risk-dashboard
   needs. Domain-specific conventions (endpoint paths, R2 key shapes,
   cache-control values, the research-lens ETL invocation) live here,
   NOT in the primitives. Thesium-career will have its own adapter with
   its own conventions.

   Pass-through to `core`."
  (:require [ai.miniforge.bb-adapter-thesium-risk.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Public API — pass-through only

(defn daily!
  "Run the daily Pro publish pipeline: build data plane → refresh →
   download snapshot → run research-lens ETL → upload both to R2.
   `cfg` shape: see `core/defaults`."
  [cfg]
  (core/daily! cfg))

(defn weekly!
  "Run the weekly community-preview publish pipeline (snapshot only).
   `cfg` shape: see `core/defaults`."
  [cfg]
  (core/weekly! cfg))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (daily! {})
  (weekly! {})

  :leave-this-here)
