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
(ns ai.miniforge.compliance-scanner.scan-rule-selector
  "Rule/category selector matching, split out of `scan` (rule 210: an
   eighth real layer there is the signal to split it). Used by
   `scan-pack-config/filter-pack-rules` to resolve a `:rules` option
   set of keywords against each pack rule's ID or Dewey category.

   Layer 0: Dewey category-range table
   Layer 1: Category-range matching
   Layer 2: Rule-ID-or-category selector matching")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private category-dewey-ranges
  "Category keyword name to Dewey code range [lo hi].
   Static data — no runtime dependency on taxonomy component."
  {"foundations"   [0 99]
   "tools"         [100 199]
   "languages"     [200 299]
   "frameworks"    [300 399]
   "testing"       [400 499]
   "operations"    [500 599]
   "documentation" [600 699]
   "workflows"     [700 799]
   "project"       [800 899]
   "meta"          [900 999]})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} category-matches?
  "Check if a rule's Dewey category falls in a category selector's range.
   Uses static range lookup — no cross-layer dependency."
  [rule cat-kw]
  (let [cat-name (name cat-kw)
        rule-cat (:rule/category rule)]
    (or (= cat-name rule-cat)
        (when-let [[lo hi] (get category-dewey-ranges cat-name)]
          (try
            (let [code (Integer/parseInt (str rule-cat))]
              (and (<= lo code) (<= code hi)))
            (catch Exception _ false))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} rule-matches-selector?
  "Check if a rule matches a selector element (keyword — rule ID or category ID)."
  [rule selector-kw]
  (or (= selector-kw (:rule/id rule))
      (category-matches? rule selector-kw)))
