;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.selection-test
  "Tests for behavioral-rule and file selection."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.semantic-analyzer.selection :as sut]))

;------------------------------------------------------------------------------ Layer 0

;; ============================================================================
;; File selection
;; ============================================================================
(deftest ^{:stratum 0} file-matches-globs-test
  (testing "matches Clojure globs"
    (is (some? (sut/file-matches-globs? "src/core.clj" ["**/*.clj"]))))

  (testing "rejects non-matching extensions"
    (is (nil? (sut/file-matches-globs? "src/core.py" ["**/*.clj"]))))

  (testing "matches nested directory patterns"
    (is (some? (sut/file-matches-globs? "components/foo/src/bar.clj"
                                        ["**/*.clj"])))))

(deftest ^{:stratum 0} select-files-for-rule-test
  (testing "selects files matching rule globs from current repo"
    (let [rule   {:rule/applies-to {:file-globs ["**/*.clj"]}}
          files  (sut/select-files-for-rule "." rule)]
      (is (seq files))
      (is (every? #(.endsWith (.getName %) ".clj") files)))))

;; ============================================================================
;; Behavioral rule filtering
;; ============================================================================
(deftest ^{:stratum 0} behavioral-rules-test
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
