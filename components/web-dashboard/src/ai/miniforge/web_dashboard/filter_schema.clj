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

(ns ai.miniforge.web-dashboard.filter-schema
  "Malli schemas for filter specification validation."
  (:require [malli.core :as m]))

(def FilterValueSpec
  "Schema for filter value extraction specification."
  [:or
   ;; Path-based extraction
   [:map
    [:kind [:= :path]]
    [:path [:vector keyword?]]]

   ;; Derived field extraction
   [:map
    [:kind [:= :derived]]
    [:derived/id keyword?]]

   ;; Multi-path extraction (for text search)
   [:map
    [:kind [:= :multi-path]]
    [:paths [:vector [:vector keyword?]]]]])

(def FilterSpec
  "Schema for a filter specification.

   Filter specs define filterable fields including:
   - ID and label for display
   - Type (enum, bool, text, date-range, number-range)
   - Scope (global context or pane-local analysis)
   - Applicability (which panes this filter applies to)
   - Value extraction (how to get the value from an item)
   - Available values (for enum types)"
  [:map
   [:filter/id keyword?]
   [:filter/label string?]
   [:filter/type [:enum :enum :bool :text :date-range :number-range]]
   [:filter/scope [:enum :global :local]]
   [:filter/applicable-to [:set keyword?]]
   [:filter/value FilterValueSpec]
   [:filter/values {:optional true}
    [:or
     [:= :dynamic]
     [:vector [:or keyword? string?]]]]])

(def FilterSpecs
  "Schema for a collection of filter specifications."
  [:vector FilterSpec])

(defn valid-filter-spec?
  "Validate a single filter specification."
  [spec]
  (m/validate FilterSpec spec))

(defn valid-filter-specs?
  "Validate a collection of filter specifications."
  [specs]
  (m/validate FilterSpecs specs))

(defn explain-filter-spec
  "Get human-readable explanation of validation errors."
  [spec]
  (m/explain FilterSpec spec))
