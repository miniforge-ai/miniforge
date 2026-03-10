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
   Loads built-in rules from a classpath EDN resource.

   Layer 0: Behavior extraction and formatting
   Layer 1: Rule loading (built-in + user packs)
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

;------------------------------------------------------------------------------ Layer 1
;; Rule loading

(defn load-builtin-rules
  "Load built-in rules from the classpath EDN resource.

   Reads packs/miniforge-builtin.pack.edn from the classpath,
   parses with edn/read-string, and extracts :pack/rules.
   Normalizes :phases vectors to sets (matching the loader pattern).

   Returns:
   - Vector of rules, or empty vector on failure"
  []
  (try
    (if-let [resource (io/resource "packs/miniforge-builtin.pack.edn")]
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
   2. Optionally load user packs from ~/.miniforge/packs/
   3. Combine all rules, filter by phase
   4. Extract behaviors and format as prompt addendum

   Arguments:
   - phase - Keyword phase (e.g. :implement)
   - context - Context map passed to filter-applicable-rules

   Returns:
   - Formatted string addendum or nil. Fail-safe (catches all exceptions)."
  [phase context]
  (try
    (let [builtin-rules (load-builtin-rules)
          user-rules (load-user-pack-rules)
          all-rules (into builtin-rules user-rules)
          ;; Use requiring-resolve for filter-applicable-rules (soft dep on policy-pack)
          filtered (if-let [filter-fn (requiring-resolve
                                        'ai.miniforge.policy-pack.core/filter-applicable-rules)]
                     (filter-fn all-rules (assoc context :phase phase))
                     ;; Fallback: manual phase filtering
                     (filterv (fn [rule]
                                (let [phases (get-in rule [:rule/applies-to :phases])]
                                  (or (nil? phases)
                                      (empty? phases)
                                      (contains? phases phase))))
                              all-rules))
          behaviors (extract-agent-behaviors filtered)]
      (format-behavior-addendum behaviors))
    (catch Exception _
      nil)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load built-in rules
  (load-builtin-rules)

  ;; Full pipeline for :implement phase
  (load-and-filter-behaviors :implement {:task {:task/intent {:intent/type :implement}}})

  ;; Extract behaviors from rules
  (extract-agent-behaviors [{:rule/agent-behavior "Check existing files"}
                            {:rule/agent-behavior nil}
                            {:rule/agent-behavior "Validate inputs"}])
  ;; => ["Check existing files" "Validate inputs"]

  ;; Format as prompt addendum
  (format-behavior-addendum ["Check existing files" "Validate inputs"])

  :leave-this-here)
