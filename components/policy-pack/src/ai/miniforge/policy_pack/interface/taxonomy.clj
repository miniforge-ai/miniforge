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

(def Taxonomy
  "Malli schema (a `[:map ...]` vector) for a taxonomy artifact — an
   independently versioned category tree with id, version, title, categories,
   and optional aliases."
  taxonomy/Taxonomy)

(def TaxonomyCategory
  "Malli schema (a `[:map ...]` vector) for a single taxonomy category —
   :category/id, :code, :title, optional :parent, and an integer :order."
  taxonomy/TaxonomyCategory)

(def TaxonomyAlias
  "Malli schema (a `[:map ...]` vector) for a taxonomy alias — a stable
   :alias/name keyword pointing at an :alias/target category ID."
  taxonomy/TaxonomyAlias)

(def TaxonomyRef
  "Malli schema (a `[:map ...]` vector) for a pack's taxonomy reference —
   :taxonomy/id and :taxonomy/min-version."
  taxonomy/TaxonomyRef)

;------------------------------------------------------------------------------ Layer 0
;; Validation

(def valid-taxonomy?
  "Predicate. Returns true when the value conforms to the Taxonomy schema,
   false otherwise."
  taxonomy/valid-taxonomy?)

(def validate-taxonomy
  "Validate a taxonomy artifact against the Taxonomy schema. Returns
   {:valid? bool :errors map-or-nil}."
  taxonomy/validate-taxonomy)

;------------------------------------------------------------------------------ Layer 1
;; Loading

(def load-taxonomy
  "Load a taxonomy artifact from an EDN file or classpath resource.
   (load-taxonomy path) returns {:success? true :taxonomy <Taxonomy>} or
   {:success? false :error <message>}."
  taxonomy/load-taxonomy)

(def load-taxonomy-from-classpath
  "Load a taxonomy artifact from a classpath resource path.
   (load-taxonomy-from-classpath resource-path) returns
   {:success? true :taxonomy <Taxonomy>} or {:success? false :error <message>}
   (including when the resource is not found)."
  taxonomy/load-taxonomy-from-classpath)

;------------------------------------------------------------------------------ Layer 1
;; Lookups

(def category-by-id
  "Look up a category by its keyword ID in a loaded taxonomy.
   (category-by-id taxonomy category-id) returns the TaxonomyCategory map, or
   nil if not present."
  taxonomy/category-by-id)

(def resolve-alias
  "Resolve an alias keyword to its target category ID.
   (resolve-alias taxonomy alias-kw) returns the target keyword, or the input
   keyword unchanged when no alias matches."
  taxonomy/resolve-alias)

(def category-title
  "Get the display title for a category ID, resolving aliases first.
   (category-title taxonomy category-id) returns the title string, or nil if
   not found."
  taxonomy/category-title)

(def category-order
  "Get the integer sort order for a category ID, resolving aliases first.
   (category-order taxonomy category-id) returns the order int, or
   Integer/MAX_VALUE if not found."
  taxonomy/category-order)
