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

(ns ai.miniforge.web-dashboard.filters-new
  "Filter system interface - data-driven hybrid filtering.

   Provides:
   - Filter spec loading from EDN
   - Malli validation
   - AST evaluation
   - Faceted counts
   - Scope-aware filtering (global + pane-local)"
  (:require
   [ai.miniforge.web-dashboard.filter-specs :as specs]
   [ai.miniforge.web-dashboard.filter-eval :as eval]
   [ai.miniforge.web-dashboard.filter-facets :as facets]))

;;------------------------------------------------------------------------------
;; Public API

;; Spec management
(def get-filter-specs specs/get-filter-specs)
(def reload-filter-specs! specs/reload-filter-specs!)
(def add-custom-filter-spec! specs/add-custom-filter-spec!)
(def get-applicable-filters specs/get-applicable-filters)
(def get-filter-spec-by-id specs/get-filter-spec-by-id)

;; Filter evaluation
(def eval-filter-ast eval/eval-filter-ast)
(def apply-filters eval/apply-filters)
(def merge-filter-state eval/merge-filter-state)

;; Facet computation
(def compute-facets facets/compute-facets)
(def compute-all-facets facets/compute-all-facets)
