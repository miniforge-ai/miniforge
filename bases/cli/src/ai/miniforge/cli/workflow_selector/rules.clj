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
(ns ai.miniforge.cli.workflow-selector.rules
  "Selection-rule matching for workflow selection.

   Split out of `ai.miniforge.cli.workflow-selector` (rule 210: the
   combined namespace measured 5 real layers, max 3) — the individual
   rule matchers and the ordered rule list they compose into live here,
   operating on the feature map produced by
   `ai.miniforge.cli.workflow-selector.spec-analysis`. `match-rule`
   itself (dispatch over `selection-rules`) stays in the parent
   namespace: it is a 4th real layer on top of this file's 3 (selection
   result -> matchers -> ordered list), and the cross-namespace call
   doesn't add to this file's own layer depth.

   Layer 0: Selection-result construction
   Layer 1: Individual rule matchers
   Layer 2: Ordered rule list"
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} selection-result
  "Build a selection result from a logical profile."
  [profile confidence reason]
  {:selection-profile profile
   :workflow-type (selection-config/resolve-selection-profile profile)
   :confidence confidence
   :reason reason})

;------------------------------------------------------------------------------ Layer 1

;; Rule matching
(defn ^{:stratum 1} match-multi-phase-rule
  "Multi-phase implementation → comprehensive profile"
  [features]
  (when (and (:pr-count features)
             (>= (:pr-count features) 4))
    (selection-result :comprehensive
                      :high
                      (messages/t :selector/reason-multi-phase
                                  {:pr-count (:pr-count features)}))))

(defn ^{:stratum 1} match-refactoring-stratification-rule
  "Refactoring with stratification → comprehensive profile"
  [features]
  (when (and (or (= (:type features) :refactoring)
                 (contains? (:keywords features) :refactoring))
             (or (contains? (:keywords features) :stratified-design)
                 (contains? (:constraint-mentions features) :rule-210)))
    (selection-result :comprehensive
                      :high
                      (messages/t :selector/reason-refactoring-stratification))))

(defn ^{:stratum 1} match-large-feature-rule
  "Large feature → comprehensive profile"
  [features]
  (when (and (= (:size features) :large)
             (not (contains? (:keywords features) :bugfix))
             (not (contains? (:keywords features) :docs-only)))
    (selection-result :comprehensive
                      :medium
                      (messages/t :selector/reason-large-feature))))

(defn ^{:stratum 1} match-bugfix-rule
  "Bug fix → fast profile"
  [features]
  (when (or (= (:type features) :bugfix)
            (contains? (:keywords features) :bugfix))
    (selection-result :fast
                      :high
                      (messages/t :selector/reason-bugfix))))

(defn ^{:stratum 1} match-docs-only-rule
  "Docs only → fast profile"
  [features]
  (when (or (= (:type features) :docs)
            (contains? (:keywords features) :docs-only))
    (selection-result :fast
                      :high
                      (messages/t :selector/reason-docs-only))))

(defn ^{:stratum 1} match-unknown-rule
  "Unknown/ambiguous → default profile"
  [_features]
  (selection-result :default
                    :low
                    (messages/t :selector/reason-unknown)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} selection-rules
  "Ordered list of selection rules. First matching rule wins."
  [match-multi-phase-rule
   match-refactoring-stratification-rule
   match-large-feature-rule
   match-bugfix-rule
   match-docs-only-rule
   match-unknown-rule])

;------------------------------------------------------------------------------ Rich Comment
(comment
  (some (fn [rule-fn] (rule-fn {:type :bugfix :keywords #{:bugfix}}))
        selection-rules)
  ;; => {:selection-profile :fast, :workflow-type :quick-fix,
  ;;     :confidence :high, :reason "..."}

  :end)
