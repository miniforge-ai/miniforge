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

(ns ai.miniforge.compliance-scanner.plan-test
  "Tests for the plan phase."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.miniforge.compliance-scanner.plan :as plan]))

;; ---------------------------------------------------------------------------
;; Test fixtures

(defn- make-violation
  [rule-id rule-category file line auto-fixable?]
  {:rule/id       rule-id
   :rule/category rule-category
   :rule/title    (str "Rule " rule-category)
   :file          file
   :line          line
   :current       (str "violation-" rule-category)
   :suggested     nil
   :auto-fixable? auto-fixable?
   :rationale     "test"})

(def ^:private file-a "components/foo/src/core.clj")
(def ^:private file-b "components/bar/src/core.clj")

;; ---------------------------------------------------------------------------
;; DAG topology

(deftest same-file-different-rules-creates-deps
  (testing "two rules on the same file produce an intra-file dep edge"
    (let [viols [(make-violation :std/clojure          "210" file-a 10 true)
                 (make-violation :std/header-copyright "810" file-a 1  true)]
          {:keys [dag-tasks]} (plan/plan viols ".")]
      ;; Should be 2 tasks (one per [file x rule] pair)
      (is (= 2 (count dag-tasks)))
      ;; The 810 task depends on the 210 task (higher Dewey depends on lower)
      (let [task-210 (first (filter #(= :std/clojure          (:task/rule-id %)) dag-tasks))
            task-810 (first (filter #(= :std/header-copyright (:task/rule-id %)) dag-tasks))]
        (is (some? task-210))
        (is (some? task-810))
        ;; 210 has no deps (lowest Dewey in the file)
        (is (empty? (:task/deps task-210)))
        ;; 810 depends on 210
        (is (contains? (:task/deps task-810) (:task/id task-210)))))))

(deftest different-files-have-no-deps
  (testing "violations on different files produce tasks with no cross-file deps"
    (let [viols [(make-violation :std/clojure "210" file-a 10 true)
                 (make-violation :std/clojure "210" file-b 5  true)]
          {:keys [dag-tasks]} (plan/plan viols ".")]
      (is (= 2 (count dag-tasks)))
      ;; Each task has no deps (separate files)
      (is (every? #(empty? (:task/deps %)) dag-tasks)))))

(deftest single-violation-has-no-deps
  (testing "a plan with one violation produces a task with an empty dep set"
    (let [viols [(make-violation :std/datever "730" file-a 3 true)]
          {:keys [dag-tasks]} (plan/plan viols ".")]
      (is (= 1 (count dag-tasks)))
      (is (empty? (:task/deps (first dag-tasks)))))))

;; ---------------------------------------------------------------------------
;; Summary statistics

(deftest summary-stats-are-correct
  (testing "plan summary counts match the violation list"
    (let [viols [(make-violation :std/clojure          "210" file-a 10 true)
                 (make-violation :std/header-copyright "810" file-a 1  true)
                 (make-violation :std/clojure          "210" file-b 5  false)
                 (make-violation :std/datever          "730" file-b 3  true)]
          {:keys [summary]} (plan/plan viols ".")]
      (is (= 4 (:total-violations summary)))
      (is (= 3 (:auto-fixable summary)))
      (is (= 1 (:needs-review summary)))
      (is (= 2 (:files-affected summary)))
      (is (= 3 (:rules-violated summary))))))

;; ---------------------------------------------------------------------------
;; Markdown work spec

(deftest work-spec-contains-expected-sections
  (testing "work spec markdown includes all required section headers"
    (let [viols [(make-violation :std/clojure          "210" file-a 10 true)
                 (make-violation :std/header-copyright "810" file-b 1  false)]
          {:keys [work-spec]} (plan/plan viols ".")]
      (is (str/includes? work-spec "# Compliance Remediation Plan"))
      (is (str/includes? work-spec "## Executive Summary"))
      (is (str/includes? work-spec "## Violations by Rule"))
      (is (str/includes? work-spec "## Needs-Review Summary"))
      (is (str/includes? work-spec "## Execution Instructions")))))

(deftest work-spec-lists-rule-categories
  (testing "work spec includes each rule category in section headers"
    (let [viols [(make-violation :std/clojure  "210" file-a 10 true)
                 (make-violation :std/datever  "730" file-b 3  true)]
          {:keys [work-spec]} (plan/plan viols ".")]
      (is (str/includes? work-spec "210 —"))
      (is (str/includes? work-spec "730 —")))))

(deftest work-spec-no-violations-shows-no-review-message
  (testing "work spec says no violations need review when all are auto-fixable"
    (let [viols [(make-violation :std/clojure "210" file-a 10 true)]
          {:keys [work-spec]} (plan/plan viols ".")]
      (is (str/includes? work-spec "_No violations require manual review._")))))

;; ---------------------------------------------------------------------------
;; Task structure

(deftest tasks-have-required-keys
  (testing "every PlanTask has all required keys"
    (let [viols [(make-violation :std/clojure          "210" file-a 10 true)
                 (make-violation :std/header-copyright "810" file-b 1  false)]
          {:keys [dag-tasks]} (plan/plan viols ".")]
      (doseq [t dag-tasks]
        (is (uuid? (:task/id t)))
        (is (set? (:task/deps t)))
        (is (string? (:task/file t)))
        (is (keyword? (:task/rule-id t)))
        (is (vector? (:task/violations t)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.compliance-scanner.plan-test)
  :leave-this-here)
