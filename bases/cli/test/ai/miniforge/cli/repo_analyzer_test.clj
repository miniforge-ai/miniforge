;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.repo-analyzer-test
  "Tests for data-driven repository analysis."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.repo-analyzer :as sut]))

(deftest fingerprints-load-test
  (testing "fingerprints load from classpath"
    (is (seq @sut/fingerprints))
    (is (vector? @sut/fingerprints))))

(deftest detect-languages-test
  (testing "detects Clojure in miniforge repo"
    (is (contains? (sut/detect-languages ".") :clojure))))

(deftest detect-markers-test
  (testing "detects Polylith and Babashka in miniforge repo"
    (let [markers (sut/detect-markers ".")]
      (is (contains? markers :polylith))
      (is (contains? markers :babashka))
      (is (contains? markers :deps-edn)))))

(deftest select-packs-test
  (testing "always includes foundations"
    (is (some #{"foundations-1.0.0"} (sut/select-packs #{}))))

  (testing "adds terraform pack for terraform repos"
    (is (some #{"terraform-aws-1.0.0"} (sut/select-packs #{:terraform}))))

  (testing "adds kubernetes pack for k8s"
    (is (some #{"kubernetes-1.0.0"} (sut/select-packs #{:kubernetes})))))

(deftest analyze-repo-test
  (testing "full analysis returns technologies and packs"
    (let [result (sut/analyze-repo ".")]
      (is (set? (:technologies result)))
      (is (contains? (:technologies result) :clojure))
      (is (vector? (:packs result)))
      (is (seq (:packs result))))))
