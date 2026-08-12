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

   Category schemas (TaxonomyCategory/TaxonomyAlias/TaxonomyRef, the
   composed Taxonomy schema) and the pure category-by-id/resolve-alias/
   category-title/category-order lookups live in
   `ai.miniforge.policy-pack.taxonomy.category` (rule 210: this combined
   namespace measured 4 real layers, max 3). This namespace keeps malli
   validation and EDN/classpath loading, which build on that schema.

   Layer 0: valid-taxonomy?, validate-taxonomy (over the Taxonomy schema)
   Layer 1: load-taxonomy, load-taxonomy-from-classpath (over
     validate-taxonomy)

   Related:
     specs/normative/N4-policy-packs.md §2.1 — Taxonomy artifact spec
     docs/design/policy-pack-taxonomy.md     — Four-artifact model design"
  (:require
   [ai.miniforge.policy-pack.taxonomy.category :as category]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0

;; Validation
(defn ^{:stratum 0} valid-taxonomy?
  [value]
  (m/validate category/Taxonomy value))

(defn ^{:stratum 0} validate-taxonomy
  [value]
  (if (m/validate category/Taxonomy value)
    {:valid? true :errors nil}
    {:valid? false
     :errors (me/humanize (m/explain category/Taxonomy value))}))

;------------------------------------------------------------------------------ Layer 1

;; Loading
(defn ^{:stratum 1} load-taxonomy
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

(defn ^{:stratum 1} load-taxonomy-from-classpath
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
