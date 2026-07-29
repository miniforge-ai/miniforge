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
(ns ai.miniforge.policy-pack.builders
  "Pack/rule construction and multi-pack rule resolution.

   Split out of core.clj (Wave 2, SL003): the constructors (packs, rules,
   detection configs, enforcement configs) plus the rule-merge/resolve chain
   that composes over `enf/stricter-enforcement` and `enf/more-severe`.

   Layer 0: create-pack, add-rule-to-pack, remove-rule-from-pack,
     update-pack-categories, create-rule, content-scan-detection,
     diff-analysis-detection, plan-output-detection, warn-enforcement,
     halt-enforcement, approval-enforcement — pure construction, no
     same-file dependents; merge-rules (over enf/stricter-enforcement and
     enf/more-severe, both external — no same-file dependents either)
   Layer 1: resolve-rules (over merge-rules)

   Handles multi-pack rule resolution with:
   - Merge by category
   - Override by ID (later pack wins)
   - Severity escalation (higher wins)
   - Enforcement escalation (stricter wins)

   The require below aliases `enforcement.clj` as `enf`, not `enforcement` —
   `create-rule`'s `enforcement` parameter (the enforcement-config argument,
   matching its original core.clj name) would otherwise shadow the alias
   inside that function's scope."
  (:require
   [ai.miniforge.policy-pack.enforcement :as enf]))

;------------------------------------------------------------------------------ Layer 0

;; Pack operations
(defn ^{:stratum 0} create-pack
  "Create a new policy pack with default values.

   Arguments:
   - id - Pack ID string
   - name - Human-readable name
   - description - Pack description
   - author - Author string

   Options:
   - :version - DateVer string (default: today's date)
   - :license - License string
   - :rules - Vector of rules

   Returns:
   - PackManifest"
  [id name description author & {:keys [version license rules]
                                  :or {rules []}}]
  (let [now (java.time.Instant/now)
        version (or version
                    (let [date (java.time.LocalDate/now)]
                      (format "%d.%02d.%02d"
                              (.getYear date)
                              (.getMonthValue date)
                              (.getDayOfMonth date))))]
    {:pack/id id
     :pack/name name
     :pack/version version
     :pack/description description
     :pack/author author
     :pack/license license
     :pack/categories []
     :pack/rules (vec rules)
     :pack/created-at now
     :pack/updated-at now}))

(defn ^{:stratum 0} add-rule-to-pack
  "Add a rule to a pack.

   Arguments:
   - pack - PackManifest
   - rule - Rule to add

   Returns:
   - Updated pack"
  [pack rule]
  (-> pack
      (update :pack/rules conj rule)
      (assoc :pack/updated-at (java.time.Instant/now))))

(defn ^{:stratum 0} remove-rule-from-pack
  "Remove a rule from a pack by ID.

   Arguments:
   - pack - PackManifest
   - rule-id - Rule ID keyword

   Returns:
   - Updated pack"
  [pack rule-id]
  (-> pack
      (update :pack/rules (fn [rules]
                            (vec (remove #(= rule-id (:rule/id %)) rules))))
      (assoc :pack/updated-at (java.time.Instant/now))))

(defn ^{:stratum 0} update-pack-categories
  "Update pack categories based on rules.

   Automatically generates categories from rule category codes.

   Arguments:
   - pack - PackManifest

   Returns:
   - Updated pack with regenerated categories"
  [pack]
  (let [rules (:pack/rules pack)
        by-category (group-by :rule/category rules)
        categories (->> by-category
                        (map (fn [[cat-id rules]]
                               {:category/id cat-id
                                :category/name (str "Category " cat-id)
                                :category/rules (mapv :rule/id rules)}))
                        (sort-by :category/id)
                        vec)]
    (assoc pack :pack/categories categories)))

;; Rule creation helpers
(defn ^{:stratum 0} create-rule
  "Create a new rule with required fields.

   Arguments:
   - id - Rule ID keyword (e.g., :310-import-preservation)
   - title - Short title
   - description - Full description
   - severity - :critical, :high, :medium, :low, or :info
   - category - Dewey category string (e.g., \"310\")
   - detection - Detection config map
   - enforcement - Enforcement config map

   Options:
   - :applies-to - Applicability config
   - :agent-behavior - Agent guidance string
   - :examples - Vector of example maps

   Returns:
   - Rule map"
  [id title description severity category detection enforcement
   & {:keys [applies-to agent-behavior examples]}]
  {:rule/id id
   :rule/title title
   :rule/description description
   :rule/severity severity
   :rule/category category
   :rule/applies-to (or applies-to {})
   :rule/detection detection
   :rule/enforcement enforcement
   :rule/agent-behavior agent-behavior
   :rule/examples examples})

(defn ^{:stratum 0} content-scan-detection
  "Create a content-scan detection config.

   Arguments:
   - pattern - Regex pattern string or compiled pattern
   - opts - Optional map with :context-lines

   Returns:
   - Detection config map"
  ([pattern]
   (content-scan-detection pattern {}))
  ([pattern {:keys [context-lines] :or {context-lines 2}}]
   {:type :content-scan
    :pattern pattern
    :context-lines context-lines}))

(defn ^{:stratum 0} diff-analysis-detection
  "Create a diff-analysis detection config.

   Arguments:
   - pattern - Regex pattern string or compiled pattern
   - opts - Optional map with :context-lines

   Returns:
   - Detection config map"
  ([pattern]
   (diff-analysis-detection pattern {}))
  ([pattern {:keys [context-lines] :or {context-lines 3}}]
   {:type :diff-analysis
    :pattern pattern
    :context-lines context-lines}))

(defn ^{:stratum 0} plan-output-detection
  "Create a plan-output detection config.

   Arguments:
   - patterns - Vector of regex patterns

   Returns:
   - Detection config map"
  [patterns]
  {:type :plan-output
   :patterns patterns})

(defn ^{:stratum 0} warn-enforcement
  "Create a warn-only enforcement config.

   Arguments:
   - message - Violation message

   Returns:
   - Enforcement config map"
  [message]
  {:action :warn
   :message message})

(defn ^{:stratum 0} halt-enforcement
  "Create a hard-halt enforcement config.

   Arguments:
   - message - Violation message
   - opts - Optional map with :remediation

   Returns:
   - Enforcement config map"
  ([message]
   (halt-enforcement message {}))
  ([message {:keys [remediation]}]
   (cond-> {:action :hard-halt
            :message message}
     remediation (assoc :remediation remediation))))

(defn ^{:stratum 0} approval-enforcement
  "Create a require-approval enforcement config.

   Arguments:
   - message - Violation message
   - approvers - Vector of approver types

   Returns:
   - Enforcement config map"
  [message approvers]
  {:action :require-approval
   :message message
   :approvers approvers})

;; Rule merging
(defn ^{:stratum 0} merge-rules
  "Merge two rules with the same ID.

   Resolution strategy:
   - Later rule overrides base rule for most fields
   - Severity: higher severity wins
   - Enforcement action: stricter action wins
   - Description: concatenate with separator if different

   Arguments:
   - base - Original rule
   - override - Rule that overrides base

   Returns:
   - Merged rule"
  [base override]
  (let [base-severity (:rule/severity base)
        override-severity (:rule/severity override)
        base-enforcement (get-in base [:rule/enforcement :action])
        override-enforcement (get-in override [:rule/enforcement :action])]
    (-> (merge base override)
        ;; Escalate severity
        (assoc :rule/severity (enf/more-severe base-severity override-severity))
        ;; Escalate enforcement
        (assoc-in [:rule/enforcement :action]
                  (enf/stricter-enforcement base-enforcement override-enforcement)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-rules
  "Resolve a collection of rules from multiple packs.

   Resolution:
   1. Group rules by ID
   2. For each ID, merge all rules using merge-rules
   3. Return deduplicated rules

   Arguments:
   - rules - Sequence of rules from multiple packs (in order of precedence)

   Returns:
   - Vector of resolved rules"
  [rules]
  (let [by-id (group-by :rule/id rules)]
    (->> by-id
         (map (fn [[_id group]]
                (reduce merge-rules group)))
         vec)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test rule merging
  (merge-rules
   {:rule/id :test
    :rule/severity :high
    :rule/enforcement {:action :warn :message "Warning"}}
   {:rule/id :test
    :rule/severity :low  ; Less severe, but original keeps high
    :rule/enforcement {:action :hard-halt :message "Halt"}})
  ;; => severity stays :high, enforcement escalates to :hard-halt

  ;; Create a pack
  (def test-pack
    (create-pack "test" "Test Pack" "A test" "author"
                 :rules
                 [(create-rule :no-todos
                               "No TODOs"
                               "Don't leave TODOs in code"
                               :low "800"
                               (content-scan-detection "TODO")
                               (warn-enforcement "Found TODO comment"))]))

  ;; Resolve rules from multiple packs
  (resolve-rules
   [{:rule/id :test :rule/severity :low :rule/enforcement {:action :warn}}
    {:rule/id :test :rule/severity :high :rule/enforcement {:action :hard-halt}}
    {:rule/id :other :rule/severity :info :rule/enforcement {:action :audit}}])

  :leave-this-here)
