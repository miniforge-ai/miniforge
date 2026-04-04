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

(ns ai.miniforge.web-dashboard.filter-eval
  "Filter AST evaluation and application."
  (:require
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.filter-specs :as specs]))

;------------------------------------------------------------------------------ Layer 0
;; Value extraction

(defn extract-value
  "Extract value from item using filter spec."
  [item {:keys [filter/value]}]
  (case (:kind value)
    :path
    (get-in item (:path value))

    :derived
    (if-let [extractor (get specs/derived-fields (:derived/id value))]
      (extractor item)
      nil)

    :multi-path
    (keep #(get-in item %) (:paths value))

    nil))

;------------------------------------------------------------------------------ Layer 1
;; Clause evaluation

(defn eval-clause
  "Evaluate a single filter clause against an item."
  [item {:keys [filter/id op value]}]
  (let [spec (specs/get-filter-spec-by-id id)
        item-value (extract-value item spec)]
    (case op
      := (= item-value value)
      :!= (not= item-value value)
      :in (contains? (set value) item-value)
      :contains (and (string? item-value)
                     (str/includes? (str/lower-case item-value)
                                   (str/lower-case value)))
      :< (and (number? item-value) (< item-value value))
      :> (and (number? item-value) (> item-value value))
      :<= (and (number? item-value) (<= item-value value))
      :>= (and (number? item-value) (>= item-value value))
      :between (and (number? item-value)
                    (>= item-value (:min value))
                    (<= item-value (:max value)))
      :text-search (some #(and (string? %)
                              (str/includes? (str/lower-case %)
                                           (str/lower-case value)))
                         item-value)
      true)))

;------------------------------------------------------------------------------ Layer 2
;; AST evaluation

(defn eval-filter-ast
  "Evaluate filter AST against an item.

   AST format:
   {:op :and | :or | :not
    :clauses [...]}

   Returns true if item matches filter."
  [item ast]
  (case (:op ast)
    :and (every? #(eval-clause item %) (:clauses ast))
    :or (some #(eval-clause item %) (:clauses ast))
    :not (not (eval-filter-ast item (first (:clauses ast))))
    true))

;------------------------------------------------------------------------------ Layer 3
;; Filter application

(defn apply-filters
  "Apply filter AST to a collection of items.

   Arguments:
   - items: Collection to filter
   - ast: Filter AST
   - pane: Current pane keyword

   Returns: Filtered collection"
  [items ast pane]
  (if (or (nil? ast)
          (empty? (:clauses ast)))
    items
    (let [;; Filter to only applicable clauses for this pane
          clauses (:clauses ast)
          applicable-clauses (filter (fn [clause]
                                      (let [spec (specs/get-filter-spec-by-id (:filter/id clause))]
                                        (and spec
                                             (contains? (:filter/applicable-to spec) pane))))
                                    clauses)
          ast' (assoc ast :clauses applicable-clauses)]
      (filter #(eval-filter-ast % ast') items))))

(defn merge-filter-state
  "Merge global and pane-local filters into a single AST.

   Arguments:
   - global-filters: Global filter AST
   - pane-filters: Pane-local filter AST

   Returns: Combined AST"
  [global-filters pane-filters]
  (let [global-clauses (:clauses global-filters [])
        pane-clauses (:clauses pane-filters [])]
    {:op :and
     :clauses (concat global-clauses pane-clauses)}))
