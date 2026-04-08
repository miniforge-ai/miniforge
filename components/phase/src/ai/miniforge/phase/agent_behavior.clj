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

(ns ai.miniforge.phase.agent-behavior
  "Extract :rule/agent-behavior from policy packs and format as prompt addendum.

   Bridges policy-pack rules and agent prompts. Uses requiring-resolve for
   the policy-pack dependency (same soft-dependency pattern as event-stream).
   Loads built-in rules and standards rules from classpath EDN resources.

   Always-inject semantics:
   Rules with :rule/always-inject? true bypass file-glob and task-type
   context matching — they are injected whenever the phase matches.
   Rules without always-inject? require full applicability matching.

   Layer 0: Behavior extraction and formatting
   Layer 1: Rule loading (built-in + standards + user packs)
   Layer 2: Full pipeline"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Behavior extraction

(defn extract-agent-behaviors
  "Extract non-nil :rule/agent-behavior strings from a seq of rules.

   Arguments:
   - rules - Seq of rule maps

   Returns:
   - Vector of behavior strings"
  [rules]
  (->> rules
       (map :rule/agent-behavior)
       (filterv some?)))

(defn extract-knowledge-content
  "Extract non-nil :rule/knowledge-content strings from a seq of rules.

   Arguments:
   - rules - Seq of rule maps

   Returns:
   - Vector of {:rule/id keyword :rule/title string :content string}"
  [rules]
  (->> rules
       (filter :rule/knowledge-content)
       (mapv (fn [r]
               {:rule/id    (:rule/id r)
                :rule/title (:rule/title r)
                :content    (:rule/knowledge-content r)}))))

(defn format-behavior-addendum
  "Format behavior strings as a numbered markdown section for prompt injection.

   Arguments:
   - behaviors - Seq of behavior strings

   Returns:
   - Formatted string or nil if no behaviors"
  [behaviors]
  (when (seq behaviors)
    (str "\n\n## Policy Rules \u2014 Required Behaviors\n\n"
         (->> behaviors
              (map-indexed (fn [i b] (str (inc i) ". " b)))
              (str/join "\n"))
         "\n")))

(defn format-knowledge-addendum
  "Format knowledge content as a reference material section for prompt injection.

   Arguments:
   - knowledge - Seq of {:rule/id :rule/title :content} maps

   Returns:
   - Formatted string or nil if no knowledge"
  [knowledge]
  (when (seq knowledge)
    (str "\n\n## Reference Material\n\n"
         (->> knowledge
              (map (fn [{:keys [rule/title content]}]
                     (str "### " (or title "Standard") "\n\n" content)))
              (str/join "\n\n"))
         "\n")))

;------------------------------------------------------------------------------ Layer 0
;; Phase filtering helpers

(defn- rule-matches-phase?
  "Check if a rule applies to the given phase.
   Rules with nil or empty phases match all phases."
  [rule phase]
  (let [phases (get-in rule [:rule/applies-to :phases])]
    (or (nil? phases)
        (empty? phases)
        (contains? phases phase))))

;------------------------------------------------------------------------------ Layer 1
;; Rule loading

(defn- load-pack-rules-from-resource
  "Load rules from a classpath EDN pack resource.

   Reads the given resource path, parses with edn/read-string,
   extracts :pack/rules, and normalizes :phases vectors to sets.

   Arguments:
   - resource-path - Classpath resource path (e.g. \"packs/miniforge-builtin.pack.edn\")

   Returns:
   - Vector of rules, or empty vector on failure."
  [resource-path]
  (try
    (if-let [resource (io/resource resource-path)]
      (let [pack (edn/read-string (slurp resource))
            rules (:pack/rules pack [])]
        ;; Normalize :phases vectors to sets for filter-applicable-rules compatibility
        (mapv (fn [rule]
                (if-let [phases (get-in rule [:rule/applies-to :phases])]
                  (assoc-in rule [:rule/applies-to :phases] (set phases))
                  rule))
              rules))
      [])
    (catch Exception _
      [])))

(defn load-builtin-rules
  "Load built-in rules from the classpath EDN resource.

   Reads packs/miniforge-builtin.pack.edn from the classpath,
   parses with edn/read-string, and extracts :pack/rules.
   Normalizes :phases vectors to sets (matching the loader pattern).

   Returns:
   - Vector of rules, or empty vector on failure"
  []
  (load-pack-rules-from-resource "packs/miniforge-builtin.pack.edn"))

(defn load-standards-rules
  "Load engineering standards rules from the classpath EDN resource.

   Reads packs/miniforge-standards.pack.edn from the classpath.
   These rules are compiled from .standards/*.mdc files by the ETL
   (bb standards:pack) and placed on the classpath at build time.

   Returns:
   - Vector of rules, or empty vector if pack not yet compiled."
  []
  (load-pack-rules-from-resource "packs/miniforge-standards.pack.edn"))

(defn load-user-pack-rules
  "Load rules from user packs in ~/.miniforge/packs/ via requiring-resolve.

   Uses the same soft-dependency pattern as event-stream to avoid
   a hard dependency on the policy-pack component.

   Returns:
   - Vector of rules, or empty vector on failure"
  []
  (try
    (let [packs-dir (io/file (System/getProperty "user.home") ".miniforge" "packs")]
      (if (.isDirectory packs-dir)
        (when-let [load-all (requiring-resolve 'ai.miniforge.policy-pack.interface/load-all-packs)]
          (let [result (load-all (str packs-dir))]
            (->> (:loaded result)
                 (mapcat :pack/rules)
                 vec)))
        []))
    (catch Exception _
      [])))

;------------------------------------------------------------------------------ Layer 2
;; Full pipeline

(defn load-and-filter-behaviors
  "Full pipeline: load rules, filter by phase, extract and format behaviors.

   1. Load built-in rules from classpath EDN resource
   2. Load standards rules from classpath EDN resource
   3. Optionally load user packs from ~/.miniforge/packs/
   4. Split rules by :rule/always-inject? flag
   5. Always-inject rules: phase-only filtering (bypass file-glob/task-type)
   6. Other rules: full context filtering via filter-applicable-rules
   7. Extract :rule/agent-behavior strings and format as prompt addendum

   Arguments:
   - phase - Keyword phase (e.g. :implement)
   - context - Context map passed to filter-applicable-rules

   Returns:
   - Formatted string addendum or nil. Fail-safe (catches all exceptions)."
  [phase context]
  (try
    (let [builtin-rules   (load-builtin-rules)
          standards-rules (load-standards-rules)
          user-rules      (load-user-pack-rules)
          all-rules       (-> builtin-rules
                              (into standards-rules)
                              (into user-rules))

          ;; Split: always-inject rules bypass context filtering
          {always-inject true, context-gated false}
          (group-by #(boolean (:rule/always-inject? %)) all-rules)

          ;; Always-inject rules: phase-only gating
          ;; These represent alwaysApply: true standards — they are injected
          ;; whenever the phase matches, regardless of file globs or task type.
          always-matched (filterv #(rule-matches-phase? % phase)
                                  (or always-inject []))

          ;; Other rules: full context filtering
          other-matched (if-let [filter-fn (requiring-resolve
                                            'ai.miniforge.policy-pack.core/filter-applicable-rules)]
                          (filter-fn (or context-gated [])
                                     (assoc context :phase phase))
                          ;; Fallback: manual phase filtering
                          (filterv #(rule-matches-phase? % phase)
                                   (or context-gated [])))

          filtered  (into always-matched other-matched)
          behaviors (extract-agent-behaviors filtered)
          knowledge (extract-knowledge-content filtered)
          behavior-section  (format-behavior-addendum behaviors)
          knowledge-section (format-knowledge-addendum knowledge)]
      (when (or behavior-section knowledge-section)
        (str behavior-section knowledge-section)))
    (catch Exception _
      nil)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load built-in rules
  (load-builtin-rules)

  ;; Load standards rules
  (load-standards-rules)

  ;; Full pipeline for :implement phase
  (load-and-filter-behaviors :implement {:task {:task/intent {:intent/type :implement}}})

  ;; Extract behaviors from rules
  (extract-agent-behaviors [{:rule/agent-behavior "Check existing files"}
                            {:rule/agent-behavior nil}
                            {:rule/agent-behavior "Validate inputs"}])
  ;; => ["Check existing files" "Validate inputs"]

  ;; Format as prompt addendum
  (format-behavior-addendum ["Check existing files" "Validate inputs"])

  ;; Always-inject rule example
  (let [rule {:rule/id :std/stratified-design
              :rule/always-inject? true
              :rule/agent-behavior "Output a stratified plan before writing code."
              :rule/applies-to {:phases #{:plan :implement :review :verify :release}}}]
    ;; This rule is injected for :implement phase (always-inject + phase matches)
    (rule-matches-phase? rule :implement))
  ;; => true

  :leave-this-here)
