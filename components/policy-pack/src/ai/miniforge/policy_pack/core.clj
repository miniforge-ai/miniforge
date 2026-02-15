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

(ns ai.miniforge.policy-pack.core
  "Core policy pack operations.

   Layer 0: Severity and enforcement comparison
   Layer 1: Rule merging and resolution
   Layer 2: Pack composition and orchestration

   Handles multi-pack rule resolution with:
   - Merge by category
   - Override by ID (later pack wins)
   - Severity escalation (higher wins)
   - Enforcement escalation (stricter wins)"
  (:require
   [ai.miniforge.policy-pack.detection :as detection]
   [ai.miniforge.policy-pack.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Severity and enforcement comparison

(def severity-order
  "Severity levels from most to least severe."
  {:critical 0
   :major 1
   :minor 2
   :info 3})

(def enforcement-order
  "Enforcement actions from strictest to most lenient."
  {:hard-halt 0
   :require-approval 1
   :warn 2
   :audit 3})

(defn compare-severity
  "Compare two severity levels.
   Returns negative if a is more severe, positive if b is more severe, 0 if equal."
  [a b]
  (- (get severity-order a 99)
     (get severity-order b 99)))

(defn more-severe
  "Return the more severe of two severity levels."
  [a b]
  (if (neg? (compare-severity a b)) a b))

(defn compare-enforcement
  "Compare two enforcement actions.
   Returns negative if a is stricter, positive if b is stricter, 0 if equal."
  [a b]
  (- (get enforcement-order a 99)
     (get enforcement-order b 99)))

(defn stricter-enforcement
  "Return the stricter of two enforcement actions."
  [a b]
  (if (neg? (compare-enforcement a b)) a b))

;------------------------------------------------------------------------------ Layer 1
;; Rule merging

(defn merge-rules
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
        (assoc :rule/severity (more-severe base-severity override-severity))
        ;; Escalate enforcement
        (assoc-in [:rule/enforcement :action]
                  (stricter-enforcement base-enforcement override-enforcement)))))

(defn resolve-rules
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

;------------------------------------------------------------------------------ Layer 1
;; Rule applicability

(defn rule-applies-to-artifact?
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

(defn rule-applies-to-task?
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

(defn rule-applies-to-phase?
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

(defn filter-applicable-rules
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
               (and (rule-applies-to-artifact? rule artifact)
                    (rule-applies-to-task? rule task-type)
                    (rule-applies-to-phase? rule phase)))
             rules)))

;------------------------------------------------------------------------------ Layer 2
;; Pack operations

(defn create-pack
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

(defn add-rule-to-pack
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

(defn remove-rule-from-pack
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

(defn update-pack-categories
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

;------------------------------------------------------------------------------ Layer 2
;; Rule checking orchestration

(defn check-artifact
  "Check an artifact against all applicable rules from a pack.

   Arguments:
   - pack - PackManifest (or vector of packs)
   - artifact - Artifact to check
   - context - Execution context

   Returns:
   - {:passed? bool
      :violations [...]
      :warnings [...]
      :errors [...]}

   Example:
     (check-artifact pack
                     {:artifact/content \"...\" :artifact/path \"main.tf\"}
                     {:phase :implement})"
  [pack artifact context]
  (let [;; Handle single pack or vector of packs
        packs (if (vector? pack) pack [pack])
        all-rules (mapcat :pack/rules packs)
        resolved (resolve-rules all-rules)
        ctx (assoc context :artifact artifact)
        applicable (filter-applicable-rules resolved ctx)
        violations (detection/check-rules applicable artifact context)

        ;; Classify violations
        blocking (detection/blocking-violations violations)
        approvals (detection/approval-required-violations violations)
        warnings (detection/warning-violations violations)
        audits (detection/audit-violations violations)]

    {:passed? (empty? blocking)
     :violations violations
     :blocking (mapv detection/violation->error blocking)
     :require-approval (mapv detection/violation->error approvals)
     :warnings (mapv detection/violation->warning warnings)
     :audits (mapv detection/violation->warning audits)
     :read-only? (boolean (:read-only? context))}))

(defn check-artifacts
  "Check multiple artifacts against pack rules.

   Arguments:
   - pack - PackManifest (or vector of packs)
   - artifacts - Vector of artifacts
   - context - Execution context

   Returns:
   - Vector of check results, one per artifact"
  [pack artifacts context]
  (mapv #(check-artifact pack % context) artifacts))

;------------------------------------------------------------------------------ Layer 2
;; Rule creation helpers

(defn create-rule
  "Create a new rule with required fields.

   Arguments:
   - id - Rule ID keyword (e.g., :310-import-preservation)
   - title - Short title
   - description - Full description
   - severity - :critical, :major, :minor, or :info
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

(defn content-scan-detection
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

(defn diff-analysis-detection
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

(defn plan-output-detection
  "Create a plan-output detection config.

   Arguments:
   - patterns - Vector of regex patterns

   Returns:
   - Detection config map"
  [patterns]
  {:type :plan-output
   :patterns patterns})

(defn warn-enforcement
  "Create a warn-only enforcement config.

   Arguments:
   - message - Violation message

   Returns:
   - Enforcement config map"
  [message]
  {:action :warn
   :message message})

(defn halt-enforcement
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

(defn approval-enforcement
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

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test severity comparison
  (compare-severity :critical :major)  ;; => -1 (critical more severe)
  (more-severe :minor :major)          ;; => :major

  ;; Test enforcement comparison
  (compare-enforcement :hard-halt :warn)  ;; => -2 (hard-halt stricter)
  (stricter-enforcement :warn :require-approval)  ;; => :require-approval

  ;; Test rule merging
  (merge-rules
   {:rule/id :test
    :rule/severity :major
    :rule/enforcement {:action :warn :message "Warning"}}
   {:rule/id :test
    :rule/severity :minor  ; Less severe, but original keeps major
    :rule/enforcement {:action :hard-halt :message "Halt"}})
  ;; => severity stays :major, enforcement escalates to :hard-halt

  ;; Create a pack
  (def test-pack
    (create-pack "test" "Test Pack" "A test" "author"
                 :rules
                 [(create-rule :no-todos
                               "No TODOs"
                               "Don't leave TODOs in code"
                               :minor "800"
                               (content-scan-detection "TODO")
                               (warn-enforcement "Found TODO comment"))]))

  ;; Check artifact
  (check-artifact test-pack
                  {:artifact/content "# TODO: fix this"
                   :artifact/path "main.py"}
                  {})

  ;; Resolve rules from multiple packs
  (resolve-rules
   [{:rule/id :test :rule/severity :minor :rule/enforcement {:action :warn}}
    {:rule/id :test :rule/severity :major :rule/enforcement {:action :hard-halt}}
    {:rule/id :other :rule/severity :info :rule/enforcement {:action :audit}}])

  :leave-this-here)
