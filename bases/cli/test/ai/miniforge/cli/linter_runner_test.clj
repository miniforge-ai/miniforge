;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.linter-runner-test
  "Tests for linter runner — parsing, availability, orchestration."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.linter-runner :as sut]))

;; Access private parsers via var
(def parse-clippy (var-get #'sut/parse-clippy))
(def parse-clj-kondo (var-get #'sut/parse-clj-kondo))
(def parse-eslint (var-get #'sut/parse-eslint))
(def parse-ruff (var-get #'sut/parse-ruff))
(def parse-swiftlint (var-get #'sut/parse-swiftlint))
(def severity-from-level (var-get #'sut/severity-from-level))

;; ============================================================================
;; Severity mapping
;; ============================================================================

(deftest severity-from-level-test
  (testing "maps error levels to :critical"
    (is (= :critical (severity-from-level "error")))
    (is (= :critical (severity-from-level "E"))))

  (testing "maps warning levels to :major"
    (is (= :major (severity-from-level "warning")))
    (is (= :major (severity-from-level "warn"))))

  (testing "maps info levels to :minor"
    (is (= :minor (severity-from-level "info")))
    (is (= :minor (severity-from-level "convention"))))

  (testing "maps style levels to :info"
    (is (= :info (severity-from-level "style")))
    (is (= :info (severity-from-level "hint")))))

;; ============================================================================
;; clj-kondo parser
;; ============================================================================

(deftest parse-clj-kondo-test
  (testing "parses EDN findings"
    (let [output "{:findings [{:type :unresolved-symbol :filename \"src/core.clj\" :row 10 :col 5 :level :error :message \"Unresolved symbol: foo\"}]}"
          result (parse-clj-kondo output)]
      (is (= 1 (count result)))
      (is (= "src/core.clj" (:file (first result))))
      (is (= 10 (:line (first result))))
      (is (= :critical (:rule/severity (first result))))))

  (testing "returns empty for no findings"
    (is (= [] (parse-clj-kondo "{:findings []}")))))

;; ============================================================================
;; ESLint parser
;; ============================================================================

(deftest parse-eslint-test
  (testing "parses JSON array of file results"
    (let [output "[{\"filePath\":\"/src/app.js\",\"messages\":[{\"ruleId\":\"no-unused-vars\",\"severity\":2,\"message\":\"x is unused\",\"line\":5,\"column\":3}]}]"
          result (parse-eslint output)]
      (is (= 1 (count result)))
      (is (= "/src/app.js" (:file (first result))))
      (is (= :major (:rule/severity (first result)))))))

;; ============================================================================
;; Ruff parser
;; ============================================================================

(deftest parse-ruff-test
  (testing "parses JSON array of violations"
    (let [output "[{\"code\":\"F401\",\"message\":\"unused import\",\"filename\":\"app.py\",\"location\":{\"row\":1,\"column\":1},\"type\":\"warning\"}]"
          result (parse-ruff output)]
      (is (= 1 (count result)))
      (is (= "app.py" (:file (first result))))
      (is (= :major (:rule/severity (first result)))))))

;; ============================================================================
;; Orchestration
;; ============================================================================

(deftest run-all-linters-no-linters-test
  (testing "returns empty when no technologies detected"
    (let [result (sut/run-all-linters "." [] #{})]
      (is (= 0 (:total-violations result)))
      (is (empty? (:violations result))))))

(deftest run-all-linters-skips-unavailable-test
  (testing "gracefully skips unavailable linters"
    (let [fps [{:tech/id :fake-lang
                :tech/linter {:command "nonexistent-linter-xyz"
                              :args ["--lint"]
                              :parser :eslint
                              :check-cmd ["nonexistent-linter-xyz" "--version"]}}]
          result (sut/run-all-linters "." fps #{:fake-lang})]
      (is (= 0 (:total-violations result)))
      (is (false? (get-in (first (:linter-results result)) [:available?]))))))
