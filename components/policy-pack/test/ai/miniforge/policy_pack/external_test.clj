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

(ns ai.miniforge.policy-pack.external-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.external :as external]
   [ai.miniforge.policy-pack.core :as core]))

;; ============================================================================
;; Diff parsing tests
;; ============================================================================

(deftest parse-pr-diff-test
  (testing "parses single-file diff"
    (let [diff "diff --git a/main.tf b/main.tf\n--- a/main.tf\n+++ b/main.tf\n@@ -1,3 +1,4 @@\n resource \"aws_vpc\" \"main\" {\n+  enable_dns = true\n   cidr_block = var.vpc_cidr\n }"
          result (external/parse-pr-diff diff)]
      (is (= 1 (count result)))
      (is (= "main.tf" (:artifact/path (first result))))
      (is (string? (:artifact/content (first result))))
      (is (string? (:artifact/diff (first result))))))

  (testing "parses multi-file diff"
    (let [diff (str "diff --git a/a.tf b/a.tf\n--- a/a.tf\n+++ b/a.tf\n@@ -1 +1,2 @@\n+added line\n"
                    "diff --git a/b.tf b/b.tf\n--- a/b.tf\n+++ b/b.tf\n@@ -1 +1,2 @@\n+another line\n")
          result (external/parse-pr-diff diff)]
      (is (= 2 (count result)))
      (is (= "a.tf" (:artifact/path (first result))))
      (is (= "b.tf" (:artifact/path (second result))))))

  (testing "returns nil for blank input"
    (is (nil? (external/parse-pr-diff "")))
    (is (nil? (external/parse-pr-diff nil)))))

;; ============================================================================
;; Read-only mode tests
;; ============================================================================

(deftest evaluate-external-pr-read-only-test
  (testing "evaluation runs in read-only mode"
    (let [pack (core/create-pack "test" "Test" "desc" "author"
                                 :rules [(core/create-rule :no-todo "No TODOs" "desc"
                                                           :minor "800"
                                                           (core/content-scan-detection "TODO")
                                                           (core/warn-enforcement "Found TODO"))])
          result (external/evaluate-external-pr
                  pack
                  {:diff "diff --git a/test.py b/test.py\n--- a/test.py\n+++ b/test.py\n@@ -1 +1,2 @@\n+# TODO: fix this\n"})]
      (is (map? result))
      (is (contains? result :evaluation/passed?))
      (is (contains? result :evaluation/violations))
      (is (contains? result :evaluation/summary)))))

;; ============================================================================
;; Severity grouping tests
;; ============================================================================

(deftest evaluation-summary-test
  (testing "summary groups by severity"
    (let [pack (core/create-pack "test" "Test" "desc" "author"
                                 :rules [(core/create-rule :no-todo "No TODOs" "desc"
                                                           :minor "800"
                                                           (core/content-scan-detection "TODO")
                                                           (core/warn-enforcement "Found TODO"))])
          result (external/evaluate-external-pr
                  pack
                  {:diff "diff --git a/test.py b/test.py\n--- a/test.py\n+++ b/test.py\n@@ -1 +1,2 @@\n+# TODO: fix\n"})]
      (is (contains? (:evaluation/summary result) :critical))
      (is (contains? (:evaluation/summary result) :major))
      (is (contains? (:evaluation/summary result) :minor))
      (is (contains? (:evaluation/summary result) :info))
      (is (contains? (:evaluation/summary result) :total)))))

;; ============================================================================
;; Report structure tests
;; ============================================================================

(deftest report-structure-test
  (testing "report has all required keys"
    (let [pack (core/create-pack "test" "Test" "desc" "author")
          result (external/evaluate-external-pr pack {:diff ""})]
      (is (boolean? (:evaluation/passed? result)))
      (is (vector? (:evaluation/violations result)))
      (is (map? (:evaluation/summary result)))
      (is (int? (:evaluation/artifacts-checked result)))
      (is (vector? (:evaluation/packs-applied result)))))

  (testing "no diff yields no violations"
    (let [pack (core/create-pack "test" "Test" "desc" "author")
          result (external/evaluate-external-pr pack {:diff ""})]
      (is (true? (:evaluation/passed? result)))
      (is (empty? (:evaluation/violations result)))
      (is (= 0 (:evaluation/artifacts-checked result))))))
