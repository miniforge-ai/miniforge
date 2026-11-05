;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.prompts-test
  "Tests for judge prompt construction."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.semantic-analyzer.prompts :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} build-judge-prompt-test
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
