;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.semantic-analyzer.core-test
  "Tests for the repo-scan analysis scope, against a mock LLM."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.semantic-analyzer.core :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private base-rule
  {:rule/id :std/test
   :rule/title "Test"
   :rule/category "001"
   :rule/severity :low
   :rule/knowledge-content "Test rule."
   :rule/applies-to {:file-globs ["src/*.clj"]}})

(defn- ^{:stratum 0} with-temp-repo
  "Create a tiny repo fixture for analyzer tests."
  [f]
  (let [dir  (.toFile (java.nio.file.Files/createTempDirectory "semantic-analyzer-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        src  (io/file dir "src")
        file (io/file src "core.clj")]
    (.mkdirs src)
    (spit file "(ns sample.core)\n(defn ok [] :ok)\n")
    (try
      (f (.getPath dir))
      (finally
        (doseq [child (reverse (file-seq dir))]
          (.delete child))))))

;; ============================================================================
;; Analyze rule with mock LLM (integration)
;; ============================================================================
(deftest ^{:stratum 0} analyze-rule-integration-test
  (testing "analyzes files and aggregates violations"
    (let [rule {:rule/id :std/test
                :rule/title "Test Rule"
                :rule/category "001"
                :rule/severity :low
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

;------------------------------------------------------------------------------ Layer 1

;; ============================================================================
;; Parallel execution with timeouts
;; ============================================================================
(deftest ^{:stratum 1} analyze-rules-parallel-test
  (testing "runs multiple rules in parallel and collects results"
    (let [rules [(assoc base-rule :rule/id :std/r1)
                 (assoc base-rule :rule/id :std/r2)
                 (assoc base-rule :rule/id :std/r3)]
          mock-complete (fn [_client _request]
                          {:success true :content "[]"})
          results (with-temp-repo
                    #(sut/analyze-rules-parallel :mock mock-complete % rules
                                                 {:timeout-ms 30000 :max-parallel 3}))]
      (is (= 3 (count results)))
      (is (every? #(= :completed (:status %)) results))))

  (testing "timed-out rules return timeout status"
    (let [slow-rule    (assoc base-rule :rule/id :std/slow :rule/title "Slow")
          mock-complete (fn [_client _request]
                          (Thread/sleep 10000)
                          {:success true :content "[]"})
          results (with-temp-repo
                    #(sut/analyze-rules-parallel :mock mock-complete % [slow-rule]
                                                 {:timeout-ms 1000 :max-parallel 1}))]
      (is (= 1 (count results)))
      (is (= :timeout (:status (first results)))))))
