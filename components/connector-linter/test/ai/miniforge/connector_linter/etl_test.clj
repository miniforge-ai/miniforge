;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.connector-linter.etl-test
  "Tests for the data-driven ETL engine — field extraction, format parsing, mapping application."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.connector-linter.etl :as sut]))

;; Access private functions
(def extract-field (var-get #'sut/extract-field))
(def map-severity (var-get #'sut/map-severity))
(def matches-filter? (var-get #'sut/matches-filter?))

;; ============================================================================
;; Field extraction
;; ============================================================================

(deftest extract-field-keyword-test
  (testing "extracts by keyword"
    (is (= "foo.clj" (extract-field {:filename "foo.clj"} :filename)))))

(deftest extract-field-vector-path-test
  (testing "extracts by vector path"
    (is (= 42 (extract-field {:pos {:line 42}} [:pos :line]))))

  (testing "extracts through array index"
    (is (= "bar.rs" (extract-field {:spans [{:file "bar.rs"}]} [:spans 0 :file]))))

  (testing "returns nil for missing path"
    (is (nil? (extract-field {:a 1} [:b :c])))))

;; ============================================================================
;; Severity mapping
;; ============================================================================

(deftest map-severity-test
  (testing "maps string keys"
    (is (= :critical (map-severity {"error" :critical} "error"))))

  (testing "maps keyword values by name"
    (is (= :major (map-severity {"warning" :major} :warning))))

  (testing "defaults to :minor for unknown"
    (is (= :minor (map-severity {"error" :critical} "unknown")))))

;; ============================================================================
;; Filter matching
;; ============================================================================

(deftest matches-filter-test
  (testing "nil filter always matches"
    (is (true? (matches-filter? nil {:anything true}))))

  (testing "matches when path equals value"
    (is (true? (matches-filter? {:path [:reason] :equals "compiler-message"}
                                {:reason "compiler-message"}))))

  (testing "rejects when path doesn't equal"
    (is (false? (matches-filter? {:path [:reason] :equals "compiler-message"}
                                 {:reason "build-script"})))))

;; ============================================================================
;; Mapping registry
;; ============================================================================

(deftest mappings-load-test
  (testing "mappings load from classpath"
    (is (map? @sut/mappings))
    (is (contains? @sut/mappings :clippy))
    (is (contains? @sut/mappings :eslint))
    (is (contains? @sut/mappings :ruff))
    (is (contains? @sut/mappings :swiftlint))
    (is (contains? @sut/mappings :clj-kondo))
    (is (contains? @sut/mappings :golangci-lint))))

;; ============================================================================
;; Full mapping application — clj-kondo
;; ============================================================================

(deftest apply-mapping-clj-kondo-test
  (testing "parses EDN findings"
    (let [mapping (sut/get-mapping :clj-kondo)
          output  "{:findings [{:type :unresolved-symbol :filename \"src/core.clj\" :row 10 :col 5 :level :error :message \"Unresolved symbol: foo\"}]}"
          result  (sut/apply-mapping mapping output)]
      (is (= 1 (count result)))
      (is (= "src/core.clj" (:file (first result))))
      (is (= 10 (:line (first result))))
      (is (= :critical (:rule/severity (first result)))))))

;; ============================================================================
;; Full mapping application — ESLint (nested format)
;; ============================================================================

(deftest apply-mapping-eslint-test
  (testing "parses nested JSON with parent file path"
    (let [mapping (sut/get-mapping :eslint)
          output  "[{\"filePath\":\"/src/app.js\",\"messages\":[{\"ruleId\":\"no-unused-vars\",\"severity\":2,\"message\":\"x is unused\",\"line\":5,\"column\":3}]}]"
          result  (sut/apply-mapping mapping output)]
      (is (= 1 (count result)))
      (is (= "/src/app.js" (:file (first result))))
      (is (= :critical (:rule/severity (first result)))))))

;; ============================================================================
;; Full mapping application — Clippy (json-lines with filter)
;; ============================================================================

(deftest apply-mapping-clippy-test
  (testing "filters for compiler-message and extracts nested fields"
    (let [mapping (sut/get-mapping :clippy)
          line1   "{\"reason\":\"compiler-artifact\"}"
          line2   "{\"reason\":\"compiler-message\",\"message\":{\"message\":\"unused var\",\"level\":\"warning\",\"code\":{\"code\":\"unused_variables\"},\"spans\":[{\"file_name\":\"src/main.rs\",\"line_start\":10,\"column_start\":5}]}}"
          output  (str line1 "\n" line2)
          result  (sut/apply-mapping mapping output)]
      (is (= 1 (count result)))
      (is (= "src/main.rs" (:file (first result))))
      (is (= :major (:rule/severity (first result)))))))

;; ============================================================================
;; Full mapping application — ruff
;; ============================================================================

(deftest apply-mapping-ruff-test
  (testing "parses flat JSON array"
    (let [mapping (sut/get-mapping :ruff)
          output  "[{\"code\":\"F401\",\"message\":\"unused import\",\"filename\":\"app.py\",\"location\":{\"row\":1,\"column\":1},\"type\":\"warning\"}]"
          result  (sut/apply-mapping mapping output)]
      (is (= 1 (count result)))
      (is (= "app.py" (:file (first result))))
      (is (= :major (:rule/severity (first result)))))))

;; ============================================================================
;; Empty / error cases
;; ============================================================================

(deftest apply-mapping-empty-test
  (testing "empty output returns empty violations"
    (let [mapping (sut/get-mapping :eslint)]
      (is (= [] (sut/apply-mapping mapping "")))
      (is (= [] (sut/apply-mapping mapping "[]"))))))

(deftest apply-mapping-malformed-test
  (testing "malformed output returns empty violations"
    (let [mapping (sut/get-mapping :eslint)]
      (is (= [] (sut/apply-mapping mapping "not json"))))))
