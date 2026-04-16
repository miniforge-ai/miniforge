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

   Pack discovery order (all sources are merged):
   1. Classpath builtins  — packs/miniforge-builtin.pack.edn
   2. Classpath standards — packs/miniforge-standards.pack.edn
   3. User global packs   — ~/.miniforge/packs/
   4. Repo packs          — .miniforge/packs/ (CWD)
   5. Extra search paths  — :policy-packs :extra-search-paths from merged config

   Packs whose :pack/id appears in :policy-packs :disabled-pack-ids are skipped
   across all sources.

   Layer 0: Behavior extraction and formatting
   Layer 1: Rule loading (built-in + standards + user/repo/extra packs)
   Layer 2: Full pipeline"
  (:require [ai.miniforge.config.interface :as config]
            [clojure.edn :as edn]
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
;; Rule loading — private helpers

(defn- normalize-pack-rules
  "Normalize :phases vectors to sets in all rules of a pack map."
  [pack]
  (update pack :pack/rules
          (fn [rules]
            (mapv (fn [rule]
                    (if-let [phases (get-in rule [:rule/applies-to :phases])]
                      (assoc-in rule [:rule/applies-to :phases] (set phases))
                      rule))
                  (or rules [])))))

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
        (mapv (fn [rule]
                (if-let [phases (get-in rule [:rule/applies-to :phases])]
                  (assoc-in rule [:rule/applies-to :phases] (set phases))
                  rule))
              rules))
      [])
    (catch Exception _
      [])))

(defn- load-pack-manifest-from-resource
  "Load a full pack manifest from a classpath resource.

   Returns the manifest map (with :pack/id, :pack/rules, etc.) with
   :phases normalized to sets, or nil if the resource is missing / unreadable."
  [resource-path]
  (try
    (when-let [resource (io/resource resource-path)]
      (-> (edn/read-string (slurp resource))
          normalize-pack-rules))
    (catch Exception _ nil)))

(defn- load-policy-packs-config
  "Load the :policy-packs section from the merged user + repo configuration.

   Calls config/load-merged-config-with-repo so that repo-level overrides
   in .miniforge/config.edn take precedence over the global user config.
   Returns an empty map on any failure — never throws."
  []
  (try
    (get (config/load-merged-config-with-repo) :policy-packs {})
    (catch Exception _ {})))

(defn- collect-pack-dirs
  "Return a vector of existing pack directory paths to scan.

   Always appends ~/.miniforge/packs/ and .miniforge/packs/ when they exist.
   Appends any :extra-search-paths from policy-packs-config that resolve to
   existing directories on disk.

   Arguments:
   - policy-packs-config - Map from :policy-packs section of merged config"
  [policy-packs-config]
  (let [home-packs  (io/file (config/miniforge-home) "packs")
        repo-packs  (io/file (System/getProperty "user.dir") ".miniforge" "packs")
        extra-paths (get policy-packs-config :extra-search-paths [])]
    (cond-> []
      (.isDirectory home-packs) (conj (str home-packs))
      (.isDirectory repo-packs) (conj (str repo-packs))
      :always (into (filter #(.isDirectory (io/file %)) extra-paths)))))

(defn- load-dir-pack-rules
  "Load rules from all pack directories specified in policy-packs-config.

   Calls ai.miniforge.policy-pack.interface/load-all-packs via requiring-resolve
   (soft dependency). Skips packs whose :pack/id is in disabled-ids.

   Arguments:
   - policy-packs-config - Map from :policy-packs section of merged config
   - disabled-ids        - Set of pack ID keywords to skip

   Returns: vector of rule maps, or empty vector on failure."
  [policy-packs-config disabled-ids]
  (try
    (let [pack-dirs (collect-pack-dirs policy-packs-config)]
      (if (empty? pack-dirs)
        []
        (if-let [load-all (requiring-resolve 'ai.miniforge.policy-pack.interface/load-all-packs)]
          (vec
           (for [dir      pack-dirs
                 :let     [result (load-all dir)]
                 pack     (:loaded result)
                 :when    (not (contains? disabled-ids (:pack/id pack)))
                 rule     (:pack/rules pack)]
             rule))
          [])))
    (catch Exception _ [])))

;------------------------------------------------------------------------------ Layer 1
;; Rule loading — public functions (kept for backward compatibility / direct use)

(defn load-builtin-rules
  "Load built-in rules from the classpath EDN resource.

   Reads packs/miniforge-builtin.pack.edn from the classpath,
   parses with edn/read-string, and extracts :pack/rules.
   Normalizes :phases vectors to sets.

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
  "Load rules from all user-configured pack directories.

   Scans (in order):
   - ~/.miniforge/packs/  — user global packs
   - .miniforge/packs/    — repo-level packs (relative to CWD)
   - :extra-search-paths  — from :policy-packs merged config

   Skips packs whose :pack/id appears in :policy-packs :disabled-pack-ids.

   Uses policy-packs config from the merged user + repo configuration.
   Provided for standalone use; load-and-filter-behaviors loads config once
   internally to avoid redundant reads.

   Returns:
   - Vector of rules, or empty vector on failure"
  []
  (try
    (let [policy-packs (load-policy-packs-config)
          disabled-ids (set (get policy-packs :disabled-pack-ids []))]
      (load-dir-pack-rules policy-packs disabled-ids))
    (catch Exception _ [])))

;------------------------------------------------------------------------------ Layer 2
;; Full pipeline

(defn load-and-filter-behaviors
  "Full pipeline: load rules from all sources, filter by phase, extract and format.

   Pack discovery order:
   1. Load builtin + standards packs from classpath
   2. Load user/repo/extra-path packs from disk (via policy-pack component)
   All sources are subject to :disabled-pack-ids filtering.

   Rule filtering:
   3. Split rules by :rule/always-inject?
   4. Always-inject rules: phase-only gating (bypass file-glob/task-type)
   5. Other rules: full context filtering via filter-applicable-rules
   6. Extract :rule/agent-behavior and :rule/knowledge-content, format addenda

   Arguments:
   - phase   - Keyword phase (e.g. :implement)
   - context - Context map passed to filter-applicable-rules

   Returns:
   - Formatted string addendum or nil. Fail-safe (catches all exceptions)."
  [phase context]
  (try
    (let [policy-packs   (load-policy-packs-config)
          disabled-ids   (set (get policy-packs :disabled-pack-ids []))

          ;; Classpath packs — always included, subject to disabled-ids
          builtin-pack   (load-pack-manifest-from-resource "packs/miniforge-builtin.pack.edn")
          standards-pack (load-pack-manifest-from-resource "packs/miniforge-standards.pack.edn")

          classpath-rules (vec
                           (for [pack  [builtin-pack standards-pack]
                                 :when (and pack
                                            (not (contains? disabled-ids (:pack/id pack))))
                                 rule  (:pack/rules pack)]
                             rule))

          ;; Directory packs — user global + repo + extra-search-paths
          dir-rules (load-dir-pack-rules policy-packs disabled-ids)

          all-rules (into classpath-rules dir-rules)

          ;; Split: always-inject rules bypass context filtering
          {always-inject true, context-gated false}
          (group-by #(boolean (:rule/always-inject? %)) all-rules)

          ;; Always-inject: phase-only gating
          ;; These represent alwaysApply: true standards — injected whenever
          ;; the phase matches, regardless of file globs or task type.
          always-matched (filterv #(rule-matches-phase? % phase)
                                  (or always-inject []))

          ;; Other rules: full context filtering
          other-matched (if-let [filter-fn (requiring-resolve
                                            'ai.miniforge.policy-pack.core/filter-applicable-rules)]
                          (filter-fn (or context-gated [])
                                     (assoc context :phase phase))
                          ;; Fallback when policy-pack component is unavailable
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
  ;; Load built-in rules (direct, no config)
  (load-builtin-rules)

  ;; Load standards rules (direct, no config)
  (load-standards-rules)

  ;; Load user + repo + extra pack rules (reads merged config)
  (load-user-pack-rules)

  ;; Inspect effective policy-packs config (merged user + repo)
  (load-policy-packs-config)

  ;; Inspect directories that would be scanned
  (collect-pack-dirs {:extra-search-paths ["/tmp/my-packs"]})

  ;; Full pipeline for :implement phase
  (load-and-filter-behaviors :implement {:task {:task/intent {:intent/type :implement}}})

  ;; Full pipeline with a disabled pack
  ;; (configure in .miniforge/config.edn or ~/.miniforge/config.edn:)
  ;; {:policy-packs {:disabled-pack-ids [:my-org/legacy-pack]}}
  (load-and-filter-behaviors :implement {})

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
    (rule-matches-phase? rule :implement))
  ;; => true

  :leave-this-here)
