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
   profile, then resolves that profile to the active app's workflow id.

   Feature extraction and individual rule matchers live in sibling
   `ai.miniforge.cli.workflow-selector.*` namespaces (rule 210: the
   combined namespace measured 5 real layers, max 3) — `analyze-spec`
   is re-exported from `spec-analysis` here so this stays the single
   public entry point. `match-rule` itself stays here rather than in
   `rules`: dispatching over `rules/selection-rules` is a 4th real
   layer on top of that namespace's own 3 (selection result -> rule
   matchers -> ordered list), and the cross-namespace call doesn't add
   to this file's own layer depth.

   Layer 0: Explanation formatting, spec-analysis re-export, rule dispatch
   Layer 1: Workflow selection with reasoning"
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-selector.rules :as rules]
   [ai.miniforge.cli.workflow-selector.spec-analysis :as spec-analysis]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} analyze-spec
  "Analyze spec and extract decision features.

   Returns map with:
   - :type - Task type (:feature, :refactoring, :bugfix, :docs, etc.)
   - :implementation-plan - Implementation plan structure
   - :pr-count - Number of PRs/phases
   - :has-dependencies? - Whether phases have dependencies
   - :keywords - Set of extracted keywords
   - :size - Estimated size (:small, :medium, :large, :unknown)
   - :constraint-mentions - Set of mentioned constraints"
  spec-analysis/analyze-spec)

(defn ^{:stratum 0} match-rule
  "Apply selection rules to features and return first match.

   Rules are applied in order:
   1. Multi-phase implementation → comprehensive profile
   2. Refactoring with stratification → comprehensive profile
   3. Large feature → comprehensive profile
   4. Bug fix → fast profile
   5. Docs only → fast profile
   6. Unknown → default profile

   Returns map with:
   - :selection-profile - Logical profile the rule matched (:comprehensive, :fast, or :default)
   - :workflow-type - Selected workflow keyword
   - :confidence - :high, :medium, or :low
   - :reason - Human-readable explanation"
  [features]
  (some (fn [rule-fn] (rule-fn features)) rules/selection-rules))

(defn ^{:stratum 0} explain-selection
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
                            :medium (messages/t :selector/confidence-medium)
                            :low (messages/t :selector/confidence-low)
                            "")]
    (str (messages/t :selector/auto-selected
                     {:workflow (name workflow-type)
                      :confidence-marker confidence-marker}) "\n"
         (messages/t :selector/reason {:reason reason}) "\n"
         (messages/t :selector/override-hint))))

;------------------------------------------------------------------------------ Layer 1

;; Workflow selection with reasoning
(defn ^{:stratum 1} select-workflow
  "Select appropriate workflow based on spec analysis.

   Returns map with:
   - :workflow-type - Selected workflow keyword
   - :confidence - :high, :medium, or :low
   - :reason - Human-readable explanation
   - :features - Extracted features used for decision

   Example:
     (select-workflow spec)
     => {:workflow-type :canonical-sdlc-v1
         :selection-profile :comprehensive
         :confidence :high
         :reason \"Multi-phase implementation with 6 PRs requires comprehensive review\"
         :features {...}}"
  [spec]
  (let [features (analyze-spec spec)
        selection (match-rule features)]
    (assoc selection :features features)))

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
  ;; => {:workflow-type :canonical-sdlc-v1
  ;;     :selection-profile :comprehensive
  ;;     :confidence :high, ...}

  ;; Test with bug fix spec
  (def bugfix-spec
    {:spec/title "Fix authentication timeout"
     :spec/description "Fix bug where auth token expires too quickly"
     :spec/intent {:type :bugfix}})

  (select-workflow bugfix-spec)
  ;; => {:workflow-type :lean-sdlc-v1
  ;;     :selection-profile :fast
  ;;     :confidence :high, ...}

  ;; Test with explicit override
  (def override-spec
    {:spec/title "Custom workflow"
     :spec/workflow-type :simple-test-v1
     :spec/description "Simple test"})

  ;; Caller should check :spec/workflow-type first
  (:spec/workflow-type override-spec)
  ;; => :simple-test-v1

  :end)
