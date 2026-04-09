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

(ns ai.miniforge.compliance-scanner.multi-detection-test
  "Tests for multi-detection scanning, category selectors, and incremental mode."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.compliance-scanner.scan]))

;; Access private functions via var for unit testing
(def ^:private category-matches?
  (var-get #'ai.miniforge.compliance-scanner.scan/category-matches?))

(def ^:private rule-matches-selector?
  (var-get #'ai.miniforge.compliance-scanner.scan/rule-matches-selector?))

(def ^:private category-dewey-ranges
  (var-get #'ai.miniforge.compliance-scanner.scan/category-dewey-ranges))

;; ============================================================================
;; Category Dewey ranges data
;; ============================================================================

(deftest category-dewey-ranges-test
  (testing "has all 10 categories"
    (is (= 10 (count category-dewey-ranges))))

  (testing "ranges are contiguous and non-overlapping"
    (let [sorted (sort-by (comp first val) (vec category-dewey-ranges))]
      (doseq [[[_k1 [_lo1 hi1]] [_k2 [lo2 _hi2]]] (partition 2 1 sorted)]
        (is (= (inc hi1) lo2))))))

;; ============================================================================
;; Category matching
;; ============================================================================

(deftest category-matches-exact-test
  (testing "exact name match"
    (let [rule {:rule/category "foundations"}]
      (is (true? (category-matches? rule :mf.cat/foundations)))))

  (testing "no match for different category"
    (let [rule {:rule/category "foundations"}]
      (is (not (category-matches? rule :mf.cat/languages))))))

(deftest category-matches-dewey-range-test
  (testing "Dewey code 210 matches :mf.cat/languages (200-299)"
    (let [rule {:rule/category "210"}]
      (is (true? (category-matches? rule :mf.cat/languages)))))

  (testing "Dewey code 810 matches :mf.cat/project (800-899)"
    (let [rule {:rule/category "810"}]
      (is (true? (category-matches? rule :mf.cat/project)))))

  (testing "Dewey code 001 matches :mf.cat/foundations (0-99)"
    (let [rule {:rule/category "001"}]
      (is (true? (category-matches? rule :mf.cat/foundations)))))

  (testing "Dewey code 210 does not match :mf.cat/foundations"
    (let [rule {:rule/category "210"}]
      (is (not (category-matches? rule :mf.cat/foundations))))))

;; ============================================================================
;; Rule selector matching
;; ============================================================================

(deftest rule-matches-selector-rule-id-test
  (testing "matches by rule ID"
    (let [rule {:rule/id :std/clojure :rule/category "210"}]
      (is (true? (rule-matches-selector? rule :std/clojure)))))

  (testing "does not match different rule ID"
    (let [rule {:rule/id :std/clojure :rule/category "210"}]
      (is (not (rule-matches-selector? rule :std/python))))))

(deftest rule-matches-selector-category-test
  (testing "matches by category keyword"
    (let [rule {:rule/id :std/clojure :rule/category "210"}]
      (is (true? (rule-matches-selector? rule :mf.cat/languages))))))

;; ============================================================================
;; Diff rule scanning
;; ============================================================================

(def ^:private scan-diff-rule
  (var-get #'ai.miniforge.compliance-scanner.scan/scan-diff-rule))

(deftest scan-diff-rule-test
  (testing "detects pattern in diff content"
    (let [rule-cfg {:rule/id       :test/removed-import
                    :rule/category "300"
                    :rule/title    "Removed Import"
                    :rule/detection {:type :diff-analysis :pattern "^-.*import"}}
          diff     "- import foo\n+ import bar\n  unchanged line"
          viols    (scan-diff-rule rule-cfg diff)]
      (is (= 1 (count viols)))
      (is (= :test/removed-import (:rule/id (first viols))))))

  (testing "returns empty when no match"
    (let [rule-cfg {:rule/id :test/no-match :rule/category "300"
                    :rule/title "No Match"
                    :rule/detection {:type :diff-analysis :pattern "^IMPOSSIBLE"}}
          viols    (scan-diff-rule rule-cfg "normal diff content")]
      (is (empty? viols)))))
