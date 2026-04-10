;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.semantic-analyzer.core-test
  "Tests for LLM-as-judge semantic analysis."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.semantic-analyzer.core :as sut]))

;; Access private functions
(def parse-judge-response (var-get #'sut/parse-judge-response))

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
      (is (.contains (:user result) "(defn foo [] 42)")))))

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
    (is (= [] (parse-judge-response "[{:line broken")))))

;; ============================================================================
;; Analyze file with mock LLM
;; ============================================================================

(deftest analyze-file-test
  (testing "produces violations from mock LLM response"
    (let [rule       {:rule/id :std/stratified-design
                      :rule/title "Stratified Design"
                      :rule/category "001"
                      :rule/severity :major
                      :rule/knowledge-content "Use layers."}
          mock-llm   :mock-client
          mock-complete (fn [_client _request]
                          {:success true
                           :content "[{:line 15 :current \"(require core)\" :message \"Cross-layer dependency\"}]"})
          result     (sut/analyze-file mock-llm mock-complete rule "src/foo.clj" "(ns foo (:require [core]))")]
      (is (= 1 (count result)))
      (is (= :std/stratified-design (:rule/id (first result))))
      (is (= "src/foo.clj" (:file (first result))))
      (is (= 15 (:line (first result)))))))

(deftest analyze-file-clean-test
  (testing "returns empty violations when LLM says code is clean"
    (let [rule       {:rule/id :std/clean :rule/title "Clean" :rule/category "001"
                      :rule/severity :info :rule/knowledge-content "Be clean."}
          mock-complete (fn [_client _request]
                          {:success true :content "[]"})
          result     (sut/analyze-file nil mock-complete rule "src/ok.clj" "(defn ok [] :ok)")]
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
    (is (= [] (sut/behavioral-rules [{:rule/id :x}])))))
