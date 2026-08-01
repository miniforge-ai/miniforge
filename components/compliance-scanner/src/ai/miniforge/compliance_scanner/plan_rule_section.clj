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
(ns ai.miniforge.compliance-scanner.plan-rule-section
  "Per-rule markdown section rendering, split out of `plan` (rule 210: a
   fifth real layer there is the signal to split it).

   Layer 0: Single-violation markdown row
   Layer 1: Labeled violation-group rendering
   Layer 2: Per-rule section assembly"
  (:require [ai.miniforge.compliance-scanner.messages :as msg]
            [clojure.string                           :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Markdown work-spec builder
(defn- ^{:stratum 0} violation->md-row
  "Render a single violation as a markdown list item."
  [v]
  (let [tag (if (get v :auto-fixable?) (msg/t :plan/tag-auto) (msg/t :plan/tag-review))]
    (str "- **L" (get v :line) "** `" (get v :current) "`"
         (when-let [s (get v :suggested)]
           (str " → `" s "`"))
         " [" tag "]")))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} render-violation-group
  "Render a labeled group of violations as markdown lines, or nil if empty."
  [label viols]
  (when (seq viols)
    (concat [label ""]
            (map violation->md-row viols)
            [""])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} section-for-rule
  "Render a markdown section for all violations of one rule."
  [_rule-id viols]
  (let [rule-title (get (first viols) :rule/title (str _rule-id))
        rule-cat   (get (first viols) :rule/category "?")
        auto       (filter :auto-fixable? viols)
        needs      (remove :auto-fixable? viols)]
    (str/join "\n"
      (concat
       [(msg/t :plan/rule-section-header {:category rule-cat :title rule-title})
        ""]
       (render-violation-group (msg/t :plan/auto-fixable-label) auto)
       (render-violation-group (msg/t :plan/needs-review-label) needs)))))
