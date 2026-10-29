;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.parsing-test
  "Tests for judge response parsing and violation canonicalization."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.semantic-analyzer.parsing :as sut]))

;------------------------------------------------------------------------------ Layer 0

;; Access private functions
(def ^{:stratum 0} strip-code-fences (var-get #'sut/strip-code-fences))

;; ============================================================================
;; Response parsing
;; ============================================================================
(deftest ^{:stratum 0} parse-judge-response-valid-test
  (testing "parses valid EDN vector of violations"
    (let [response "[{:line 10 :current \"(eval x)\" :message \"eval is unsafe\"}]"
          result   (sut/parse-judge-response response)]
      (is (= 1 (count result)))
      (is (= 10 (:line (first result))))))

  (testing "parses empty vector as no violations"
    (is (= [] (sut/parse-judge-response "[]"))))

  (testing "parses multiple violations"
    (let [response "[{:line 5 :message \"a\"} {:line 10 :message \"b\"}]"
          result   (sut/parse-judge-response response)]
      (is (= 2 (count result)))))

  (testing "strips markdown code fences"
    (let [response "```edn\n[{:line 5 :current \"foo\" :message \"bar\"}]\n```"
          result   (sut/parse-judge-response response)]
      (is (= 1 (count result))))))

(deftest ^{:stratum 0} parse-judge-response-invalid-test
  (testing "an answer that is not an EDN vector is no answer (nil), never
            an empty finding list"
    (is (nil? (sut/parse-judge-response "The code looks fine, no violations.")))
    (is (nil? (sut/parse-judge-response nil)))
    (is (nil? (sut/parse-judge-response "[{:line broken")))
    (is (nil? (sut/parse-judge-response "{:not \"a vector\"}")))))

;; ============================================================================
;; Violation canonicalization
;; ============================================================================
(deftest ^{:stratum 0} raw->violation-test
  (testing "produces canonical violation shape"
    (let [rule {:rule/id :std/test :rule/category "001"
                :rule/title "Test Rule" :rule/severity :high}
          v    {:line 42 :current "(bad code)" :message "This is bad"}
          result (sut/raw->violation rule "src/foo.clj" v)]
      (is (= :std/test (:rule/id result)))
      (is (= "001" (:rule/category result)))
      (is (= "Test Rule" (:rule/title result)))
      (is (= "This is bad" (:rationale result)))
      (is (= "src/foo.clj" (:file result)))
      (is (= 42 (:line result)))
      (is (= "(bad code)" (:current result)))
      (is (false? (:auto-fixable? result)))
      (is (= "This is bad" (:rationale result)))))

  (testing "defaults for missing fields"
    (let [result (sut/raw->violation {} "f.clj" {})]
      (is (= 0 (:line result)))
      (is (= "" (:current result)))
      (is (= "Semantic analysis violation" (:rationale result))))))

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Code fence stripping
;; ============================================================================
(deftest ^{:stratum 1} strip-code-fences-test
  (testing "strips edn code fences"
    (is (= "[{:line 1}]" (strip-code-fences "```edn\n[{:line 1}]\n```"))))

  (testing "strips plain code fences"
    (is (= "[{:line 1}]" (strip-code-fences "```\n[{:line 1}]\n```"))))

  (testing "leaves non-fenced content unchanged"
    (is (= "[{:line 1}]" (strip-code-fences "[{:line 1}]")))))
