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

(ns ai.miniforge.policy-pack.detection
  "Rule violation detection implementations.

   Layer 0: Pattern matching utilities
   Layer 1: Detection type implementations
   Layer 2: Unified detection dispatcher

   Supports detection types:
   - :content-scan - Regex against artifact content
   - :diff-analysis - Regex against git diff
   - :plan-output - Parse terraform plan output
   - :custom - Custom detection function"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pattern matching utilities

(defn- ensure-pattern
  "Convert string or regex to compiled pattern."
  [p]
  (cond
    (instance? java.util.regex.Pattern p) p
    (string? p) (re-pattern p)
    :else nil))

(defn- find-matches
  "Find all matches of a pattern in content.
   Returns vector of {:match string :line int :column int :context string}."
  [pattern content context-lines]
  (when-let [pat (ensure-pattern pattern)]
    (let [lines (str/split-lines content)
          context-lines (or context-lines 2)]
      (->> lines
           (map-indexed vector)
           (keep (fn [[idx line]]
                   (when-let [match (re-find pat line)]
                     (let [match-str (if (string? match) match (first match))
                           start (max 0 (- idx context-lines))
                           end (min (count lines) (+ idx context-lines 1))
                           context-vec (subvec (vec lines) start end)]
                       {:match match-str
                        :line (inc idx)  ; 1-indexed
                        :column (when-let [idx (str/index-of line match-str)]
                                  (inc idx))
                        :context (str/join "\n" context-vec)}))))
           vec))))

(defn- any-pattern-matches?
  "Check if any of the patterns match the content.
   Returns the first matching pattern's matches, or nil."
  [patterns content context-lines]
  (when (seq patterns)
    (some (fn [p]
            (let [matches (find-matches p content context-lines)]
              (when (seq matches)
                matches)))
          patterns)))

(defn- extract-patterns
  "Extract pattern(s) from detection config.
   Returns vector of patterns."
  [detection]
  (let [{:keys [pattern patterns]} detection]
    (cond
      (seq patterns) patterns
      pattern [pattern]
      :else [])))

;------------------------------------------------------------------------------ Layer 1
;; Content scan detection

(defn detect-content-scan
  "Detect violations by scanning artifact content.

   Looks for pattern matches in the artifact's content field.

   Arguments:
   - rule - Rule with :rule/detection config
   - artifact - Artifact with :artifact/content
   - context - Execution context (unused for content-scan)

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact _context]
  (let [detection (:rule/detection rule)
        content (:artifact/content artifact)
        patterns (extract-patterns detection)
        context-lines (:context-lines detection 2)]
    (when (and content (seq patterns))
      (when-let [matches (any-pattern-matches? patterns content context-lines)]
        {:type :content-scan
         :rule-id (:rule/id rule)
         :matches matches
         :artifact-path (:artifact/path artifact)
         :message (get-in rule [:rule/enforcement :message])}))))

;------------------------------------------------------------------------------ Layer 1
;; Diff analysis detection

(defn detect-diff-analysis
  "Detect violations by analyzing git diff.

   Looks for pattern matches in:
   - The artifact's diff field (if present)
   - The context's diff field

   Useful for detecting removed lines, changed patterns, etc.

   Arguments:
   - rule - Rule with :rule/detection config
   - artifact - Artifact with optional :artifact/diff
   - context - Context with optional :diff

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact context]
  (let [detection (:rule/detection rule)
        ;; Diff can be on artifact or in context
        diff (or (:artifact/diff artifact)
                 (:diff context))
        patterns (extract-patterns detection)
        context-lines (:context-lines detection 3)]
    (when (and diff (seq patterns))
      (when-let [matches (any-pattern-matches? patterns diff context-lines)]
        {:type :diff-analysis
         :rule-id (:rule/id rule)
         :matches matches
         :artifact-path (:artifact/path artifact)
         :message (get-in rule [:rule/enforcement :message])}))))

;------------------------------------------------------------------------------ Layer 1
;; Plan output detection

(defn- parse-plan-resources
  "Parse terraform plan output to extract resource changes.
   Returns vector of {:action :resource :details}."
  [plan-output]
  (when plan-output
    (let [;; Match lines like:
          ;; "# aws_route.main will be destroyed"
          ;; "# aws_subnet.private[0] must be replaced"
          ;; "# aws_vpc.main will be created"
          ;; "  -/+ aws_route.main (tainted)"
          action-patterns
          [{:pattern #"#\s+(\S+)\s+will be (created|destroyed|updated)"
            :extractor (fn [[_ resource action]]
                         {:resource resource
                          :action (case action
                                    "created" :create
                                    "destroyed" :destroy
                                    "updated" :update
                                    (keyword action))})}
           {:pattern #"#\s+(\S+)\s+must be (replaced)"
            :extractor (fn [[_ resource _action]]
                         {:resource resource
                          :action :replace})}
           {:pattern #"^\s*(-/\+|~|\+|-)\s+(\S+)"
            :extractor (fn [[_ symbol resource]]
                         {:resource resource
                          :action (case symbol
                                    "-/+" :replace
                                    "~" :update
                                    "+" :create
                                    "-" :destroy
                                    :unknown)})}]]
      (->> (str/split-lines plan-output)
           (mapcat (fn [line]
                     (keep (fn [{:keys [pattern extractor]}]
                             (when-let [match (re-find pattern line)]
                               (assoc (extractor match)
                                      :line line)))
                           action-patterns)))
           (distinct)
           vec))))

(defn detect-plan-output
  "Detect violations by analyzing terraform plan output.

   Parses plan output and checks for:
   - Resource patterns matching replace/destroy actions
   - Specific patterns in plan text

   Arguments:
   - rule - Rule with :rule/detection config
   - artifact - Artifact (may contain plan output)
   - context - Context with :terraform-plan

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact context]
  (let [detection (:rule/detection rule)
        plan-output (or (:terraform-plan context)
                        (:artifact/content artifact))
        patterns (extract-patterns detection)
        resource-patterns (get-in rule [:rule/applies-to :resource-patterns])]
    (when plan-output
      ;; Two detection modes:
      ;; 1. Pattern match against raw plan text
      ;; 2. Parse plan and check resource changes
      (let [pattern-matches (when (seq patterns)
                              (any-pattern-matches? patterns plan-output 2))
            ;; Parse resources and check for concerning actions
            parsed (parse-plan-resources plan-output)
            resource-violations
            (when (and (seq resource-patterns) (seq parsed))
              (->> parsed
                   (filter (fn [{:keys [resource action]}]
                             (and (#{:replace :destroy} action)
                                  (some (fn [rp]
                                          (re-find (ensure-pattern rp) resource))
                                        resource-patterns))))
                   seq))]
        (when (or pattern-matches resource-violations)
          {:type :plan-output
           :rule-id (:rule/id rule)
           :matches (or pattern-matches [])
           :resource-violations (vec resource-violations)
           :message (get-in rule [:rule/enforcement :message])})))))

;------------------------------------------------------------------------------ Layer 1
;; Custom detection

(defn detect-custom
  "Detect violations using a custom function.

   The custom function is specified by symbol in :custom-fn and must:
   - Accept [artifact context]
   - Return violation map or nil

   Arguments:
   - rule - Rule with :rule/detection containing :custom-fn
   - artifact - Artifact being checked
   - context - Execution context

   Returns:
   - Violation map if detected, nil otherwise"
  [rule artifact context]
  (let [detection (:rule/detection rule)
        custom-fn-sym (:custom-fn detection)]
    (when custom-fn-sym
      (try
        ;; Attempt to resolve the function
        (when-let [f (resolve custom-fn-sym)]
          (when-let [result (f artifact context)]
            (assoc result
                   :type :custom
                   :rule-id (:rule/id rule))))
        (catch Exception e
          ;; Return error as violation
          {:type :custom-error
           :rule-id (:rule/id rule)
           :error (.getMessage e)
           :message (str "Custom detection failed: " (.getMessage e))})))))

;------------------------------------------------------------------------------ Layer 2
;; Unified detection dispatcher

(defn detect-violation
  "Detect violations for a rule against an artifact.

   Dispatches to the appropriate detection implementation based on
   the rule's :rule/detection :type.

   Arguments:
   - rule - Rule with :rule/detection
   - artifact - Artifact being validated
   - context - Execution context map

   Returns:
   - Violation map if detected, nil otherwise.
   - Violation map contains:
     - :type - Detection type used
     - :rule-id - Rule that was violated
     - :matches - Pattern matches found
     - :message - Enforcement message"
  [rule artifact context]
  (let [detection-type (get-in rule [:rule/detection :type])]
    (case detection-type
      :content-scan (detect-content-scan rule artifact context)
      :diff-analysis (detect-diff-analysis rule artifact context)
      :plan-output (detect-plan-output rule artifact context)
      :custom (detect-custom rule artifact context)
      ;; Unsupported types
      :state-comparison nil  ; Not implemented
      :ast-analysis nil       ; Not implemented
      nil)))

(defn check-rules
  "Check multiple rules against an artifact.

   Returns vector of violations found.

   Arguments:
   - rules - Vector of rules to check
   - artifact - Artifact being validated
   - context - Execution context

   Returns:
   - Vector of violation maps, each with :rule and :violation keys"
  [rules artifact context]
  (->> rules
       (keep (fn [rule]
               (when-let [violation (detect-violation rule artifact context)]
                 {:rule rule
                  :violation violation
                  :timestamp (java.time.Instant/now)})))
       vec))

;------------------------------------------------------------------------------ Layer 2
;; Violation classification helpers

(defn blocking-violations
  "Filter violations that should block progress (:hard-halt enforcement)."
  [violations]
  (filter #(= :hard-halt (get-in % [:rule :rule/enforcement :action]))
          violations))

(defn approval-required-violations
  "Filter violations that require approval."
  [violations]
  (filter #(= :require-approval (get-in % [:rule :rule/enforcement :action]))
          violations))

(defn warning-violations
  "Filter violations that are warnings only."
  [violations]
  (filter #(= :warn (get-in % [:rule :rule/enforcement :action]))
          violations))

(defn audit-violations
  "Filter violations that are audit-only."
  [violations]
  (filter #(= :audit (get-in % [:rule :rule/enforcement :action]))
          violations))

(defn violation->error
  "Convert a violation to a gate error map."
  [{:keys [rule violation]}]
  {:code (:rule/id rule)
   :message (:message violation)
   :severity (:rule/severity rule)
   :location {:matches (:matches violation)
              :path (:artifact-path violation)}
   :remediation (get-in rule [:rule/enforcement :remediation])})

(defn violation->warning
  "Convert a violation to a gate warning map."
  [{:keys [rule violation]}]
  {:code (:rule/id rule)
   :message (:message violation)
   :severity (:rule/severity rule)})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test content scan detection
  (detect-content-scan
   {:rule/id :no-todos
    :rule/detection {:type :content-scan
                     :pattern "TODO"
                     :context-lines 1}
    :rule/enforcement {:action :warn
                       :message "Found TODO"}}
   {:artifact/content "def foo():\n    # TODO: implement\n    pass"
    :artifact/path "main.py"}
   {})
  ;; => {:type :content-scan, :rule-id :no-todos, :matches [...], ...}

  ;; Test diff analysis
  (detect-diff-analysis
   {:rule/id :310-import-block-preservation
    :rule/detection {:type :diff-analysis
                     :pattern "^-\\s*import\\s*\\{"
                     :context-lines 3}
    :rule/enforcement {:action :hard-halt
                       :message "Cannot remove import blocks"}}
   {:artifact/diff "- import {\n-   to = aws_s3_bucket.example\n- }"
    :artifact/path "main.tf"}
   {})

  ;; Test plan output detection
  (detect-plan-output
   {:rule/id :320-network-recreation-block
    :rule/detection {:type :plan-output
                     :patterns ["-/\\+.*aws_route"]}
    :rule/applies-to {:resource-patterns ["aws_route" "aws_subnet"]}
    :rule/enforcement {:action :require-approval
                       :message "Network resource recreation detected"}}
   {}
   {:terraform-plan "# aws_route.main will be replaced\n  -/+ aws_route.main"})

  ;; Parse plan resources
  (parse-plan-resources
   "# aws_vpc.main will be created
    # aws_subnet.private[0] will be destroyed
    # aws_route.public must be replaced
      -/+ aws_route.main (tainted)")

  ;; Check multiple rules
  (check-rules
   [{:rule/id :no-todos
     :rule/detection {:type :content-scan :pattern "TODO"}
     :rule/enforcement {:action :warn :message "Found TODO"}}
    {:rule/id :no-fixmes
     :rule/detection {:type :content-scan :pattern "FIXME"}
     :rule/enforcement {:action :warn :message "Found FIXME"}}]
   {:artifact/content "# TODO: fix this\n# FIXME: broken"}
   {})

  :leave-this-here)
