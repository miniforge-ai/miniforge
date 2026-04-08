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

(ns ai.miniforge.policy-pack.interface.taxonomy
  "Taxonomy loading, validation, and lookup."
  (:require
   [ai.miniforge.policy-pack.taxonomy :as taxonomy]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def Taxonomy taxonomy/Taxonomy)
(def TaxonomyCategory taxonomy/TaxonomyCategory)
(def TaxonomyAlias taxonomy/TaxonomyAlias)
(def TaxonomyRef taxonomy/TaxonomyRef)

;------------------------------------------------------------------------------ Layer 0
;; Validation

(def valid-taxonomy? taxonomy/valid-taxonomy?)
(def validate-taxonomy taxonomy/validate-taxonomy)

;------------------------------------------------------------------------------ Layer 1
;; Loading

(def load-taxonomy taxonomy/load-taxonomy)
(def load-taxonomy-from-classpath taxonomy/load-taxonomy-from-classpath)

;------------------------------------------------------------------------------ Layer 1
;; Lookups

(def category-by-id taxonomy/category-by-id)
(def resolve-alias taxonomy/resolve-alias)
(def category-title taxonomy/category-title)
(def category-order taxonomy/category-order)
