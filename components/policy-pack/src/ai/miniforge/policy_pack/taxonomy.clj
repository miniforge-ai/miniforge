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

(ns ai.miniforge.policy-pack.taxonomy
  "First-class taxonomy artifact — independently versioned category trees.

   Decouples classification evolution from rule definition. After loading,
   the runtime resolves category IDs to display labels and sort orders
   without any knowledge of Dewey codes or MDC format.

   Layer 0: Malli schemas for Taxonomy, TaxonomyCategory, TaxonomyAlias
   Layer 1: Loading and validation
   Layer 2: Canonical taxonomy export from mdc-compiler dewey-ranges

   Related:
     specs/normative/N4-policy-packs.md §2.1 — Taxonomy artifact spec
     docs/design/policy-pack-taxonomy.md     — Four-artifact model design"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def TaxonomyCategory
  "Schema for a single category within a taxonomy.

   :category/id     — Stable namespaced keyword, immutable after publication
   :category/code   — Display code (e.g. \"210\"); Dewey numbers, display only
   :category/title  — Human-readable label for reports
   :category/parent — Parent category ID (nil for root categories)
   :category/order  — Integer sort order; drives rule application sequence"
  [:map
   [:category/id keyword?]
   [:category/code string?]
   [:category/title string?]
   [:category/parent {:optional true} [:maybe keyword?]]
   [:category/order int?]])

(def TaxonomyAlias
  "Schema for a taxonomy alias — stable logical name to category ID mapping.
   Allows decoupling rule category references from taxonomy reorganization."
  [:map
   [:alias/name keyword?]
   [:alias/target keyword?]])

(def Taxonomy
  "Schema for a taxonomy artifact.

   Taxonomies are independently versioned category trees. Each pack
   references a taxonomy via :pack/taxonomy-ref and declares a minimum
   compatible version."
  [:map
   [:taxonomy/id keyword?]
   [:taxonomy/version string?]
   [:taxonomy/title string?]
   [:taxonomy/description {:optional true} string?]
   [:taxonomy/categories [:vector TaxonomyCategory]]
   [:taxonomy/aliases {:optional true} [:vector TaxonomyAlias]]])

(def TaxonomyRef
  "Schema for a taxonomy reference within a pack manifest.
   Packs declare which taxonomy they target and the minimum version required."
  [:map
   [:taxonomy/id keyword?]
   [:taxonomy/min-version string?]])

;------------------------------------------------------------------------------ Layer 1
;; Validation

(defn valid-taxonomy?
  "Check if value conforms to the Taxonomy schema."
  [value]
  (m/validate Taxonomy value))

(defn validate-taxonomy
  "Validate a taxonomy artifact. Returns {:valid? bool :errors map-or-nil}."
  [value]
  (if (m/validate Taxonomy value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain Taxonomy value))}))

;------------------------------------------------------------------------------ Layer 1
;; Loading

(defn load-taxonomy
  "Load a taxonomy artifact from an EDN file.

   Arguments:
   - path — Path to the .edn taxonomy file (string, File, or resource)

   Returns:
   - {:success? true :taxonomy <Taxonomy>}
   - {:success? false :error <message>}"
  [path]
  (try
    (let [resource (or (io/resource path) (io/file path))
          content  (slurp resource)
          taxonomy (edn/read-string content)]
      (if (valid-taxonomy? taxonomy)
        {:success? true :taxonomy taxonomy}
        {:success? false
         :error    (str "Taxonomy validation failed: "
                        (:errors (validate-taxonomy taxonomy)))}))
    (catch Exception e
      {:success? false :error (.getMessage e)})))

(defn load-taxonomy-from-classpath
  "Load a taxonomy from the classpath resources.

   Arguments:
   - resource-path — Classpath resource path (e.g. \"taxonomies/miniforge-dewey-1.0.0.edn\")

   Returns:
   - {:success? true :taxonomy <Taxonomy>}
   - {:success? false :error <message>}"
  [resource-path]
  (if-let [resource (io/resource resource-path)]
    (try
      (let [content  (slurp resource)
            taxonomy (edn/read-string content)]
        (if (valid-taxonomy? taxonomy)
          {:success? true :taxonomy taxonomy}
          {:success? false
           :error    (str "Taxonomy validation failed: "
                          (:errors (validate-taxonomy taxonomy)))}))
      (catch Exception e
        {:success? false :error (.getMessage e)}))
    {:success? false :error (str "Resource not found: " resource-path)}))

;------------------------------------------------------------------------------ Layer 1
;; Lookup helpers

(defn category-by-id
  "Look up a category by its keyword ID in a loaded taxonomy.
   Returns the TaxonomyCategory map, or nil."
  [taxonomy category-id]
  (some (fn [cat]
          (when (= category-id (:category/id cat))
            cat))
        (:taxonomy/categories taxonomy)))

(defn resolve-alias
  "Resolve an alias keyword to a category ID.
   Returns the target keyword, or the input if no alias matches."
  [taxonomy alias-kw]
  (or (some (fn [a]
              (when (= alias-kw (:alias/name a))
                (:alias/target a)))
            (:taxonomy/aliases taxonomy))
      alias-kw))

(defn category-title
  "Get the display title for a category ID, resolving aliases.
   Returns the title string, or nil if not found."
  [taxonomy category-id]
  (let [resolved (resolve-alias taxonomy category-id)]
    (:category/title (category-by-id taxonomy resolved))))

(defn category-order
  "Get the sort order for a category ID, resolving aliases.
   Returns the order integer, or Integer/MAX_VALUE if not found."
  [taxonomy category-id]
  (let [resolved (resolve-alias taxonomy category-id)]
    (or (:category/order (category-by-id taxonomy resolved))
        Integer/MAX_VALUE)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate a taxonomy
  (valid-taxonomy?
   {:taxonomy/id      :miniforge/dewey
    :taxonomy/version "1.0.0"
    :taxonomy/title   "Miniforge Dewey Taxonomy"
    :taxonomy/categories
    [{:category/id    :mf.cat/foundations
      :category/code  "000-099"
      :category/title "Foundations & Core Principles"
      :category/order 0}]})
  ;; => true

  ;; Load from classpath
  ;; (load-taxonomy-from-classpath "taxonomies/miniforge-dewey-1.0.0.edn")

  :leave-this-here)
