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

(ns ai.miniforge.cli.workflow-selector
  "Intelligent workflow selection based on spec characteristics.

   Analyzes workflow specs and automatically selects the appropriate workflow
   type (canonical-sdlc-v1, lean-sdlc-v1, or simple-test-v1).

   Layer 0: Spec analysis - extract features from spec
   Layer 1: Rule matching - apply selection rules
   Layer 2: Workflow selection with reasoning"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Spec feature extraction

(defn extract-type
  "Extract task type from spec.
   After normalization, :spec/intent is guaranteed to be set."
  [spec]
  (or (get-in spec [:spec/intent :type])
      (get-in spec [:spec/raw-data :type])
      :unknown))

(defn extract-implementation-plan
  "Extract implementation plan details from spec."
  [spec]
  (or (get-in spec [:spec/raw-data :implementation-plan])
      (get-in spec [:spec/intent :implementation-plan])
      (:implementation-plan spec)))

(defn count-prs
  "Count number of PRs/phases in implementation plan."
  [impl-plan]
  (when impl-plan
    (count (filter (fn [[k _v]] (str/starts-with? (name k) "pr-"))
                   impl-plan))))

(defn has-dependencies?
  "Check if implementation plan has dependencies between phases."
  [impl-plan]
  (when impl-plan
    (some (fn [[_k v]]
            (and (map? v)
                 (or (seq (:dependencies v))
                     (not-empty (:base v)))))
          impl-plan)))

(defn extract-description-keywords
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

(defn estimate-size
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

(defn extract-constraints-mentions
  "Extract constraint mentions from spec."
  [spec]
  (let [constraints (or (:spec/constraints spec) [])]
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

(defn analyze-spec
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

;------------------------------------------------------------------------------ Layer 1
;; Rule matching

(defn match-multi-phase-rule
  "Multi-phase implementation → canonical-sdlc-v1"
  [features]
  (when (and (:pr-count features)
             (>= (:pr-count features) 4))
    {:workflow-type :canonical-sdlc-v1
     :confidence :high
     :reason (str "Multi-phase implementation with "
                  (:pr-count features)
                  " PRs requires comprehensive review")}))

(defn match-refactoring-stratification-rule
  "Refactoring with stratification → canonical-sdlc-v1"
  [features]
  (when (and (or (= (:type features) :refactoring)
                 (contains? (:keywords features) :refactoring))
             (or (contains? (:keywords features) :stratified-design)
                 (contains? (:constraint-mentions features) :rule-210)))
    {:workflow-type :canonical-sdlc-v1
     :confidence :high
     :reason "Refactoring with stratification requires comprehensive design review"}))

(defn match-large-feature-rule
  "Large feature → canonical-sdlc-v1"
  [features]
  (when (and (= (:size features) :large)
             (not (contains? (:keywords features) :bugfix))
             (not (contains? (:keywords features) :docs-only)))
    {:workflow-type :canonical-sdlc-v1
     :confidence :medium
     :reason "Large feature requires comprehensive SDLC phases"}))

(defn match-bugfix-rule
  "Bug fix → lean-sdlc-v1"
  [features]
  (when (or (= (:type features) :bugfix)
            (contains? (:keywords features) :bugfix))
    {:workflow-type :lean-sdlc-v1
     :confidence :high
     :reason "Bug fix is well-suited for lean workflow"}))

(defn match-docs-only-rule
  "Docs only → lean-sdlc-v1"
  [features]
  (when (or (= (:type features) :docs)
            (contains? (:keywords features) :docs-only))
    {:workflow-type :lean-sdlc-v1
     :confidence :high
     :reason "Documentation changes use lean workflow"}))

(defn match-unknown-rule
  "Unknown/ambiguous → lean-sdlc-v1 (safer default than simple)"
  [_features]
  {:workflow-type :lean-sdlc-v1
   :confidence :low
   :reason "Unknown task type defaults to lean workflow (safer than simple)"})

(def selection-rules
  "Ordered list of selection rules. First matching rule wins."
  [match-multi-phase-rule
   match-refactoring-stratification-rule
   match-large-feature-rule
   match-bugfix-rule
   match-docs-only-rule
   match-unknown-rule])

(defn match-rule
  "Apply selection rules to features and return first match.

   Rules are applied in order:
   1. Multi-phase implementation → canonical-sdlc-v1
   2. Refactoring with stratification → canonical-sdlc-v1
   3. Large feature → canonical-sdlc-v1
   4. Bug fix → lean-sdlc-v1
   5. Docs only → lean-sdlc-v1
   6. Unknown → lean-sdlc-v1 (default)

   Returns map with:
   - :workflow-type - Selected workflow keyword
   - :confidence - :high, :medium, or :low
   - :reason - Human-readable explanation"
  [features]
  (some (fn [rule-fn] (rule-fn features)) selection-rules))

;------------------------------------------------------------------------------ Layer 2
;; Workflow selection with reasoning

(defn select-workflow
  "Select appropriate workflow based on spec analysis.

   Returns map with:
   - :workflow-type - Selected workflow keyword
   - :confidence - :high, :medium, or :low
   - :reason - Human-readable explanation
   - :features - Extracted features used for decision

   Example:
     (select-workflow spec)
     => {:workflow-type :canonical-sdlc-v1
         :confidence :high
         :reason \"Multi-phase implementation with 6 PRs requires comprehensive review\"
         :features {...}}"
  [spec]
  (let [features (analyze-spec spec)
        selection (match-rule features)]
    (assoc selection :features features)))

(defn explain-selection
  "Generate user-facing explanation for workflow selection.

   Returns string suitable for printing to console.

   Example:
     ℹ️  Auto-selected workflow: canonical-sdlc-v1
         Reason: Multi-phase refactoring with 6 PRs requires comprehensive review
         Override with :spec/workflow-type in your spec"
  [selection]
  (let [{:keys [workflow-type confidence reason]} selection
        confidence-marker (case confidence
                            :high ""
                            :medium " (medium confidence)"
                            :low " (low confidence)"
                            "")]
    (str "ℹ️  Auto-selected workflow: " (name workflow-type) confidence-marker "\n"
         "    Reason: " reason "\n"
         "    Override with :spec/workflow-type in your spec")))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test with emojui spec
  (def emojui-spec
    {:spec/title "Memento Views Refactor"
     :spec/description "Refactor 5 memory view pages to follow stratified design"
     :spec/raw-data {:type :refactoring
                     :implementation-plan
                     {:pr-2-shared-components {:branch "feature/memento-shared-components"}
                      :pr-3-stream-view {:branch "feature/memento-stream-view"}
                      :pr-4-morning-view {:branch "feature/memento-morning-view"}
                      :pr-5-garden-view {:branch "feature/memento-garden-view"}
                      :pr-6-constellation-view {:branch "feature/memento-constellation-view"}
                      :pr-7-heatmap-view {:branch "feature/memento-heatmap-view"}}}
     :spec/constraints ["Follow stratified design" "≤400 lines per file"]})

  (select-workflow emojui-spec)
  ;; => {:workflow-type :canonical-sdlc-v1, :confidence :high, ...}

  ;; Test with bug fix spec
  (def bugfix-spec
    {:spec/title "Fix authentication timeout"
     :spec/description "Fix bug where auth token expires too quickly"
     :spec/intent {:type :bugfix}})

  (select-workflow bugfix-spec)
  ;; => {:workflow-type :lean-sdlc-v1, :confidence :high, ...}

  ;; Test with explicit override
  (def override-spec
    {:spec/title "Custom workflow"
     :spec/workflow-type :simple-test-v1
     :spec/description "Simple test"})

  ;; Caller should check :spec/workflow-type first
  (:spec/workflow-type override-spec)
  ;; => :simple-test-v1

  :end)
