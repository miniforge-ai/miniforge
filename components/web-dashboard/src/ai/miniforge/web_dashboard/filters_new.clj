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
