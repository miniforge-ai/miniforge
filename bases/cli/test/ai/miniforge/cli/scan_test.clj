;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.scan-test
  "Tests for the scan CLI command — pack resolution, selector parsing, pipeline."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.main.commands.scan :as sut]))

;; Access private functions via var
(def resolve-rules-selector (var-get #'sut/resolve-rules-selector))
(def resolve-pack (var-get #'sut/resolve-pack))
(def build-scan-opts (var-get #'sut/build-scan-opts))

;; ============================================================================
;; Rule selector parsing
;; ============================================================================

(deftest resolve-rules-selector-test
  (testing "nil defaults to :all"
    (is (= :all (resolve-rules-selector nil))))

  (testing "string 'all' returns :all"
    (is (= :all (resolve-rules-selector "all"))))

  (testing "string 'always-apply' returns :always-apply"
    (is (= :always-apply (resolve-rules-selector "always-apply"))))

  (testing "other strings become keywords"
    (is (= :std/clojure (resolve-rules-selector "std/clojure")))))

;; ============================================================================
;; Pack resolution
;; ============================================================================

(deftest resolve-pack-from-classpath-test
  (testing "loads reference pack by name from classpath"
    (let [pack (resolve-pack "foundations-1.0.0")]
      (is (some? pack))
      (when pack
        (is (= "miniforge/foundations" (:pack/id pack)))
        (is (seq (:pack/rules pack))))))

  (testing "returns nil for nonexistent pack"
    (is (nil? (resolve-pack "nonexistent-pack-9999")))))

(deftest resolve-pack-from-file-test
  (testing "loads pack from file path"
    (let [pack (resolve-pack "components/compliance-scanner/resources/test-fixtures/clojure-210-pack.edn")]
      (is (some? pack))
      (when pack
        (is (seq (:pack/rules pack)))))))

;; ============================================================================
;; Scan opts construction
;; ============================================================================

(deftest build-scan-opts-test
  (testing "defaults to :all rules"
    (let [opts (build-scan-opts "." {})]
      (is (= :all (:rules opts)))))

  (testing "includes pack when provided"
    (let [opts (build-scan-opts "." {:pack "foundations-1.0.0"})]
      (is (some? (:pack opts)))
      (is (= :all (:rules opts)))))

  (testing "includes since ref when provided"
    (let [opts (build-scan-opts "." {:since "HEAD~5"})]
      (is (= "HEAD~5" (:since opts)))))

  (testing "passes rules selector through"
    (let [opts (build-scan-opts "." {:rules "always-apply"})]
      (is (= :always-apply (:rules opts))))))

;; ============================================================================
;; Negative-mode violation messages
;; ============================================================================

(deftest negative-mode-uses-rule-title-test
  (testing "negative-mode violations use rule title, not hardcoded header message"
    (let [scan-fn (requiring-resolve 'ai.miniforge.compliance-scanner.interface/scan)
          k8s     (resolve-pack "kubernetes-1.0.0")
          result  (scan-fn "." ".standards" {:rules :all :pack k8s})]
      (doseq [v (:violations result)]
        (is (not= "(missing copyright header)" (:current v))
            (str "Violation in " (:file v) " should not use hardcoded header message"))))))
