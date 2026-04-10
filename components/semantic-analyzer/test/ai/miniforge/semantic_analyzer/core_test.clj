;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.semantic-analyzer.core-test
  "Tests for LLM-as-judge semantic analysis."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.semantic-analyzer.core :as sut]))

;; Access private functions
(def parse-judge-response (var-get #'sut/parse-judge-response))
(def strip-code-fences (var-get #'sut/strip-code-fences))
(def raw->violation (var-get #'sut/raw->violation))
(def under-size-limit? (var-get #'sut/under-size-limit?))
(def file-matches-globs? (var-get #'sut/file-matches-globs?))

;; ============================================================================
;; Prompt construction
;; ============================================================================

(deftest build-judge-prompt-test
  (testing "produces system and user prompts with interpolated values"
    (let [rule   {:rule/title "Stratified Design"
                  :rule/description "Enforce one-way dependencies"
                  :rule/knowledge-content "# Stratified Design\n\nUse layers."}
          result (sut/build-judge-prompt rule "src/core.clj" "(defn foo [] 42)")]
      (is (string? (:system result)))
      (is (string? (:user result)))
      (is (.contains (:user result) "Stratified Design"))
      (is (.contains (:user result) "src/core.clj"))
      (is (.contains (:user result) "(defn foo [] 42)"))))

  (testing "handles missing rule fields gracefully"
    (let [result (sut/build-judge-prompt {} "f.clj" "code")]
      (is (string? (:system result)))
      (is (string? (:user result))))))

;; ============================================================================
;; Code fence stripping
;; ============================================================================

(deftest strip-code-fences-test
  (testing "strips edn code fences"
    (is (= "[{:line 1}]" (strip-code-fences "```edn\n[{:line 1}]\n```"))))

  (testing "strips plain code fences"
    (is (= "[{:line 1}]" (strip-code-fences "```\n[{:line 1}]\n```"))))

  (testing "leaves non-fenced content unchanged"
    (is (= "[{:line 1}]" (strip-code-fences "[{:line 1}]")))))

;; ============================================================================
;; Response parsing
;; ============================================================================

(deftest parse-judge-response-valid-test
  (testing "parses valid EDN vector of violations"
    (let [response "[{:line 10 :current \"(eval x)\" :message \"eval is unsafe\"}]"
          result   (parse-judge-response response)]
      (is (= 1 (count result)))
      (is (= 10 (:line (first result))))))

  (testing "parses empty vector as no violations"
    (is (= [] (parse-judge-response "[]"))))

  (testing "parses multiple violations"
    (let [response "[{:line 5 :message \"a\"} {:line 10 :message \"b\"}]"
          result   (parse-judge-response response)]
      (is (= 2 (count result)))))

  (testing "strips markdown code fences"
    (let [response "```edn\n[{:line 5 :current \"foo\" :message \"bar\"}]\n```"
          result   (parse-judge-response response)]
      (is (= 1 (count result))))))

(deftest parse-judge-response-invalid-test
  (testing "returns empty for non-EDN response"
    (is (= [] (parse-judge-response "The code looks fine, no violations."))))

  (testing "returns empty for nil"
    (is (= [] (parse-judge-response nil))))

  (testing "returns empty for malformed EDN"
    (is (= [] (parse-judge-response "[{:line broken"))))

  (testing "returns empty for non-vector EDN"
    (is (= [] (parse-judge-response "{:not \"a vector\"}")))))

;; ============================================================================
;; Violation canonicalization
;; ============================================================================

(deftest raw->violation-test
  (testing "produces canonical violation shape"
    (let [rule {:rule/id :std/test :rule/category "001"
                :rule/title "Test Rule" :rule/severity :major}
          v    {:line 42 :current "(bad code)" :message "This is bad"}
          result (raw->violation rule "src/foo.clj" v)]
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
    (let [result (raw->violation {} "f.clj" {})]
      (is (= 0 (:line result)))
      (is (= "" (:current result)))
      (is (= "Semantic analysis violation" (:rationale result))))))

;; ============================================================================
;; File selection
;; ============================================================================

(deftest file-matches-globs-test
  (testing "matches Clojure globs"
    (is (some? (file-matches-globs? "src/core.clj" ["**/*.clj"]))))

  (testing "rejects non-matching extensions"
    (is (nil? (file-matches-globs? "src/core.py" ["**/*.clj"]))))

  (testing "matches nested directory patterns"
    (is (some? (file-matches-globs? "components/foo/src/bar.clj"
                                     ["**/*.clj"])))))

(deftest select-files-for-rule-test
  (testing "selects files matching rule globs from current repo"
    (let [rule   {:rule/applies-to {:file-globs ["**/*.clj"]}}
          files  (sut/select-files-for-rule "." rule)]
      (is (seq files))
      (is (every? #(.endsWith (.getName %) ".clj") files)))))

;; ============================================================================
;; Analyze file with mock LLM
;; ============================================================================

(deftest analyze-file-violations-test
  (testing "produces violations from mock LLM response"
    (let [rule       {:rule/id :std/stratified-design
                      :rule/title "Stratified Design"
                      :rule/category "001"
                      :rule/severity :major
                      :rule/knowledge-content "Use layers."}
          mock-complete (fn [_client _request]
                          {:success true
                           :content "[{:line 15 :current \"(require core)\" :message \"Cross-layer dependency\"}]"})
          result     (sut/analyze-file :mock mock-complete rule "src/foo.clj" "(ns foo (:require [core]))")]
      (is (= 1 (count result)))
      (is (= :std/stratified-design (:rule/id (first result))))
      (is (= "src/foo.clj" (:file (first result))))
      (is (= 15 (:line (first result))))
      (is (= "Cross-layer dependency" (:rationale (first result)))))))

(deftest analyze-file-clean-test
  (testing "returns empty violations when LLM says code is clean"
    (let [rule       {:rule/id :std/clean :rule/title "Clean" :rule/category "001"
                      :rule/severity :info :rule/knowledge-content "Be clean."}
          mock-complete (fn [_client _request]
                          {:success true :content "[]"})
          result     (sut/analyze-file nil mock-complete rule "src/ok.clj" "(defn ok [] :ok)")]
      (is (= [] result)))))

(deftest analyze-file-llm-error-test
  (testing "returns empty violations when LLM returns non-EDN"
    (let [rule       {:rule/id :std/test :rule/title "T" :rule/category "001"
                      :rule/severity :info :rule/knowledge-content "K"}
          mock-complete (fn [_client _request]
                          {:success true :content "Sorry, I can't analyze this."})
          result     (sut/analyze-file nil mock-complete rule "f.clj" "code")]
      (is (= [] result)))))

;; ============================================================================
;; Behavioral rule filtering
;; ============================================================================

(deftest behavioral-rules-test
  (testing "filters to rules with knowledge-content"
    (let [rules [{:rule/id :a :rule/knowledge-content "Has content"}
                 {:rule/id :b}
                 {:rule/id :c :rule/knowledge-content "Also has content"}]]
      (is (= 2 (count (sut/behavioral-rules rules))))
      (is (= #{:a :c} (set (map :rule/id (sut/behavioral-rules rules)))))))

  (testing "returns empty for no behavioral rules"
    (is (= [] (sut/behavioral-rules [{:rule/id :x}]))))

  (testing "returns empty for empty input"
    (is (= [] (sut/behavioral-rules [])))))

;; ============================================================================
;; Analyze rule with mock LLM (integration)
;; ============================================================================

(deftest analyze-rule-integration-test
  (testing "analyzes files and aggregates violations"
    (let [rule {:rule/id :std/test
                :rule/title "Test Rule"
                :rule/category "001"
                :rule/severity :minor
                :rule/knowledge-content "Check for test."
                :rule/applies-to {:file-globs ["components/semantic-analyzer/test/**/*.clj"]}}
          call-count (atom 0)
          mock-complete (fn [_client _request]
                          (swap! call-count inc)
                          {:success true :content "[]"})
          result (sut/analyze-rule :mock mock-complete "." rule)]
      (is (keyword? (:rule/id result)))
      (is (vector? (:violations result)))
      (is (pos? (:files-analyzed result)))
      (is (pos? @call-count)))))

(def ^:private base-rule
  {:rule/id :std/test
   :rule/title "Test"
   :rule/category "001"
   :rule/severity :minor
   :rule/knowledge-content "Test rule."
   :rule/applies-to {:file-globs ["components/semantic-analyzer/test/**/*.clj"]}})

;; ============================================================================
;; Parallel execution with timeouts
;; ============================================================================

(deftest analyze-rules-parallel-test
  (testing "runs multiple rules in parallel and collects results"
    (let [rules [(assoc base-rule :rule/id :std/r1)
                 (assoc base-rule :rule/id :std/r2)
                 (assoc base-rule :rule/id :std/r3)]
          mock-complete (fn [_client _request]
                          {:success true :content "[]"})
          results (sut/analyze-rules-parallel :mock mock-complete "." rules
                                              {:timeout-ms 30000 :max-parallel 3})]
      (is (= 3 (count results)))
      (is (every? #(= :completed (:status %)) results))))

  (testing "timed-out rules return timeout status"
    (let [slow-rule    (assoc base-rule :rule/id :std/slow :rule/title "Slow")
          mock-complete (fn [_client _request]
                          (Thread/sleep 10000)
                          {:success true :content "[]"})
          results (sut/analyze-rules-parallel :mock mock-complete "." [slow-rule]
                                              {:timeout-ms 1000 :max-parallel 1})]
      (is (= 1 (count results)))
      (is (= :timeout (:status (first results)))))))
