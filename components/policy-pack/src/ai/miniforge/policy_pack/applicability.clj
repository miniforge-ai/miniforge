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
(ns ai.miniforge.policy-pack.applicability
  "Rule applicability — does a rule apply to this artifact/task/phase?

   Split out of core.clj (Wave 2, SL003): the per-field applicability
   predicates and their combination into one context-level filter.

   Layer 0: rule-applies-to-artifact? (file globs), rule-applies-to-task?
     (task types), rule-applies-to-phase? (workflow phases) — each a pure
     predicate over one :rule/applies-to field, no same-file dependents
   Layer 1: filter-applicable-rules (over all three Layer 0 predicates plus
     the rule's :rule/enabled? flag)"
  (:require
   [ai.miniforge.policy-pack.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

;; Rule applicability
(defn ^{:stratum 0} rule-applies-to-artifact?
  "Check if a rule applies to an artifact based on file globs.

   Arguments:
   - rule - Rule with :rule/applies-to
   - artifact - Artifact with :artifact/path

   Returns:
   - true if rule applies, false otherwise"
  [rule artifact]
  (let [file-globs (get-in rule [:rule/applies-to :file-globs])
        path (:artifact/path artifact "")]
    (or (nil? file-globs)
        (empty? file-globs)
        (some #(registry/glob-matches? % path) file-globs))))

(defn ^{:stratum 0} rule-applies-to-task?
  "Check if a rule applies to a task type.

   Arguments:
   - rule - Rule with :rule/applies-to
   - task-type - Task type keyword

   Returns:
   - true if rule applies, false otherwise"
  [rule task-type]
  (let [task-types (get-in rule [:rule/applies-to :task-types])]
    (or (nil? task-types)
        (empty? task-types)
        (contains? task-types task-type))))

(defn ^{:stratum 0} rule-applies-to-phase?
  "Check if a rule applies to a workflow phase.

   Arguments:
   - rule - Rule with :rule/applies-to
   - phase - Phase keyword

   Returns:
   - true if rule applies, false otherwise"
  [rule phase]
  (let [phases (get-in rule [:rule/applies-to :phases])]
    (or (nil? phases)
        (empty? phases)
        (contains? phases phase))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} filter-applicable-rules
  "Filter rules to those applicable to the given context.

   Arguments:
   - rules - Vector of rules
   - context - Context map with :artifact, :task, :phase

   Returns:
   - Vector of applicable rules"
  [rules context]
  (let [artifact (:artifact context)
        task-type (get-in context [:task :task/intent :intent/type])
        phase (:phase context)]
    (filterv (fn [rule]
               (and (get rule :rule/enabled? true)
                    (rule-applies-to-artifact? rule artifact)
                    (rule-applies-to-task? rule task-type)
                    (rule-applies-to-phase? rule phase)))
             rules)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (rule-applies-to-artifact? {:rule/applies-to {:file-globs ["**/*.tf"]}}
                             {:artifact/path "main.tf"})
  ;; => true

  (filter-applicable-rules
   [{:rule/id :a :rule/applies-to {:phases #{:implement}}}
    {:rule/id :b :rule/enabled? false :rule/applies-to {}}]
   {:artifact {:artifact/path "main.tf"} :phase :implement})
  ;; => [{:rule/id :a ...}] — :b excluded by :rule/enabled? false

  :leave-this-here)
