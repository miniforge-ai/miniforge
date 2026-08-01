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
(ns ai.miniforge.compliance-scanner.scan-diff-plan
  "Diff/plan/exceptions-as-data detection, split out of `scan` (rule 210:
   an eighth real layer there is the signal to split it). These are the
   scanner's non-content-scan detection types — they don't flow through
   pack-rule detection configs.

   Layer 0: Safe git-ref validation, plan-rule scanning, diff-rule
            scanning, exceptions-as-data selector
   Layer 1: Git diff retrieval, bulk diff/plan-rule scanning, and the
            exceptions-as-data dispatch"
  (:require
   [ai.miniforge.compliance-scanner.exceptions-as-data :as exc-data]
   [ai.miniforge.compliance-scanner.factory            :as factory]
   [ai.miniforge.compliance-scanner.scan-content        :as scan-content]
   [ai.miniforge.policy-pack.interface                  :as policy-pack]
   [clojure.java.io                                     :as io]
   [clojure.string                                      :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} safe-git-ref?
  "Return true iff ref contains only characters valid in a git ref argument.
   Rejects empty strings, nil, and any value starting with '-' (which git
   could interpret as an option flag)."
  [ref]
  (boolean (and (string? ref)
                (seq ref)
                (not (str/starts-with? ref "-"))
                (re-matches #"[a-zA-Z0-9/_.\-~^@{}]+" ref))))

(defn- ^{:stratum 0} scan-plan-rule
  "Scan plan output for violations using a plan-output rule.
   Uses the policy-pack detect-violation dispatcher for resource-level analysis.
   Returns vector of Violation maps."
  [rule-cfg plan-output]
  (let [rule-id  (:rule/id rule-cfg)
        rule-cat (:rule/category rule-cfg)
        title    (:rule/title rule-cfg)
        ;; Build rule and artifact maps for the detection dispatcher
        rule-map {:rule/id         rule-id
                  :rule/detection  (get rule-cfg :rule/detection)
                  :rule/applies-to (get rule-cfg :rule/applies-to {})
                  :rule/enforcement {:action :warn :message title}}
        artifact {:artifact/content plan-output}
        context  {:terraform-plan plan-output}
        result   (policy-pack/detect-violation rule-map artifact context)]
    (if result
      (let [resources (:resource-violations result [])]
        (if (seq resources)
          (mapv (fn [{:keys [resource action line]}]
                  (factory/->violation
                   rule-id rule-cat title
                   (str resource)
                   0
                   (str action " " resource " — " (str/trim (or line "")))
                   nil false ""))
                resources)
          [(factory/->violation
            rule-id rule-cat title
            "<plan-output>" 0
            (:message result)
            nil false "")]))
      [])))

;; Built-in Clojure-AST linters
;;
;; These linters do not flow through pack-rule detection configs because
;; their analysis is structural, not regex-driven. They are first-class
;; rules of the scanner and run alongside content/diff/plan rules.
(defn ^{:stratum 0} exceptions-as-data-selected?
  "True when the rules selector resolves the exceptions-as-data linter on.
   Defaults on for `:all` / `:always-apply`; opts in for explicit selectors."
  [opts]
  (let [raw       (get opts :rules :always-apply)
        requested (if (string? raw) (keyword (subs raw 1)) raw)]
    (cond
      (contains? #{:all :always-apply} requested) true
      (= exc-data/rule-id requested)              true
      (and (set? requested)
           (contains? requested exc-data/rule-id)) true
      :else false)))

(defn- ^{:stratum 0} scan-diff-rule
  "Scan a git diff for violations using a diff-analysis rule.
   Returns vector of Violation maps."
  [rule-cfg diff-content]
  (let [rule-id  (:rule/id rule-cfg)
        rule-cat (:rule/category rule-cfg)
        title    (:rule/title rule-cfg)
        pattern  (re-pattern (get-in rule-cfg [:rule/detection :pattern]))
        matches  (scan-content/positive-matches pattern diff-content)]
    (mapv (fn [{:keys [line match]}]
            (factory/->violation
             rule-id rule-cat title
             "<diff>"     ; file is the entire diff
             line match
             nil          ; no suggestion for diff violations
             false ""))
          matches)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} git-diff
  "Run git diff and return the output string."
  [repo-path since-ref]
  (when (safe-git-ref? since-ref)
    (try
      (let [pb (ProcessBuilder. ^java.util.List
                ["git" "diff" (str since-ref "..HEAD")])
            _  (.directory pb (io/file repo-path))
            proc (.start pb)
            out  (slurp (.getInputStream proc))]
        (.waitFor proc)
        (when-not (str/blank? out) out))
      (catch Exception _ nil))))

(defn ^{:stratum 1} git-diff-name-only
  "Run git diff --name-only and return set of changed file paths."
  [repo-path since-ref]
  (when (safe-git-ref? since-ref)
    (try
      (let [pb (ProcessBuilder. ^java.util.List
                ["git" "diff" "--name-only" (str since-ref "..HEAD")])
            _  (.directory pb (io/file repo-path))
            proc (.start pb)
            out  (slurp (.getInputStream proc))]
        (.waitFor proc)
        (when-not (str/blank? out)
          (set (str/split-lines (str/trim out)))))
      (catch Exception _ nil))))

(defn ^{:stratum 1} run-exceptions-as-data
  "Run the AST-based exceptions-as-data linter against the repo. Returns
   a vector of actionable Violation maps when selected, or nil when the
   rule selector leaves the linter off. Returned rows are already in the
   canonical scanner shape.

   The raw exceptions-as-data scanner preserves `:fatal-only` and
   `:local-boundary` records for audit counts, but the top-level compliance
   review treats only `:cleanup-needed` rows as standards debt.
   Programmer-error guards and documented local boundary wrappers are explicit
   rule carve-outs and should not inflate `bb review` totals.

   When `changed-files` is non-nil (incremental mode via `:since`), the
   linter restricts its file walk to the changed set — matching the
   behavior of content rules so `bb review --since` stays fast."
  [repo-path changed-files opts]
  (when (exceptions-as-data-selected? opts)
    (filterv #(= :cleanup-needed (:classification %))
             (:violations (exc-data/scan-repo repo-path changed-files)))))

(defn ^{:stratum 1} scan-plan-rules
  "Scan plan-output rules against terraform plan output."
  [plan-rules plan-output]
  (when plan-output
    (->> plan-rules
         (mapcat #(scan-plan-rule % plan-output))
         vec)))

(defn ^{:stratum 1} scan-diff-rules
  "Scan diff-analysis rules against a git diff."
  [diff-rules diff-content]
  (when diff-content
    (->> diff-rules
         (mapcat #(scan-diff-rule % diff-content))
         vec)))
