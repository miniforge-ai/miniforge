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
(ns ai.miniforge.cli.workflow-selector.spec-analysis
  "Spec feature extraction for workflow selection.

   Split out of `ai.miniforge.cli.workflow-selector` (rule 210: the
   combined namespace measured 5 real layers, max 3) — the extraction
   primitives and their `analyze-spec` composition live here; rule
   matching and the public selection entry points stay in the parent
   namespace.

   Layer 0: Field extraction primitives
   Layer 1: Size estimation (composes keyword/PR-count extraction)
   Layer 2: analyze-spec - full feature-map composition"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Spec feature extraction
(defn ^{:stratum 0} extract-type
  "Extract task type from spec.
   After normalization, :spec/intent is guaranteed to be set."
  [spec]
  (or (get-in spec [:spec/intent :type])
      (get-in spec [:spec/raw-data :type])
      :unknown))

(defn ^{:stratum 0} extract-implementation-plan
  "Extract implementation plan details from spec."
  [spec]
  (or (get-in spec [:spec/raw-data :implementation-plan])
      (get-in spec [:spec/intent :implementation-plan])
      (:implementation-plan spec)))

(defn ^{:stratum 0} count-prs
  "Count number of PRs/phases in implementation plan."
  [impl-plan]
  (when impl-plan
    (count (filter (fn [[k _v]] (str/starts-with? (name k) "pr-"))
                   impl-plan))))

(defn ^{:stratum 0} has-dependencies?
  "Check if implementation plan has dependencies between phases."
  [impl-plan]
  (when impl-plan
    (some (fn [[_k v]]
            (and (map? v)
                 (or (seq (:dependencies v))
                     (not-empty (:base v)))))
          impl-plan)))

(defn ^{:stratum 0} extract-description-keywords
  "Extract significant keywords from spec description."
  [spec]
  (let [desc (str/lower-case (or (:spec/description spec) ""))
        title (str/lower-case (or (:spec/title spec) ""))]
    (set (concat
          (when (or (str/includes? desc "refactor")
                    (str/includes? title "refactor"))
            [:refactoring])
          (when (or (str/includes? desc "stratif")
                    (str/includes? desc "layer"))
            [:stratified-design])
          (when (or (str/includes? desc "multi-phase")
                    (str/includes? desc "multiple phase")
                    (str/includes? desc "6 pr")
                    (str/includes? desc "multi-pr"))
            [:multi-phase])
          (when (or (str/includes? desc "bug")
                    (str/includes? desc "fix"))
            [:bugfix])
          (when (or (str/includes? desc "document")
                    (str/includes? desc "docs only"))
            [:docs-only])
          (when (or (str/includes? desc "large")
                    (str/includes? desc "complex")
                    (str/includes? desc "comprehensive"))
            [:large-scope])
          (when (or (str/includes? desc "simple")
                    (str/includes? desc "small")
                    (str/includes? desc "quick"))
            [:small-scope])))))

(defn ^{:stratum 0} extract-constraints-mentions
  "Extract constraint mentions from spec."
  [spec]
  (let [constraints (get spec :spec/constraints [])]
    (set (concat
          (when (some #(or (str/includes? (str/lower-case (str %)) "rule 720")
                           (str/includes? (str/lower-case (str %)) "≤400"))
                      constraints)
            [:rule-720])
          (when (some #(or (str/includes? (str/lower-case (str %)) "rule 210")
                           (str/includes? (str/lower-case (str %)) "≤3 layer")
                           (str/includes? (str/lower-case (str %)) "stratif"))
                      constraints)
            [:rule-210])
          (when (some #(str/includes? (str/lower-case (str %)) "zero lint")
                      constraints)
            [:zero-linting])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} estimate-size
  "Estimate scope size from spec."
  [spec impl-plan]
  (let [keywords (extract-description-keywords spec)
        pr-count (count-prs impl-plan)]
    (cond
      (and pr-count (>= pr-count 5)) :large
      (contains? keywords :large-scope) :large
      (contains? keywords :small-scope) :small
      (and pr-count (>= pr-count 3)) :medium
      :else :unknown)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} analyze-spec
  "Analyze spec and extract decision features.

   Returns map with:
   - :type - Task type (:feature, :refactoring, :bugfix, :docs, etc.)
   - :implementation-plan - Implementation plan structure
   - :pr-count - Number of PRs/phases
   - :has-dependencies? - Whether phases have dependencies
   - :keywords - Set of extracted keywords
   - :size - Estimated size (:small, :medium, :large, :unknown)
   - :constraint-mentions - Set of mentioned constraints"
  [spec]
  (let [task-type (extract-type spec)
        impl-plan (extract-implementation-plan spec)
        pr-count (count-prs impl-plan)
        keywords (extract-description-keywords spec)
        size (estimate-size spec impl-plan)]
    {:type task-type
     :implementation-plan impl-plan
     :pr-count pr-count
     :has-dependencies? (has-dependencies? impl-plan)
     :keywords keywords
     :size size
     :constraint-mentions (extract-constraints-mentions spec)}))
