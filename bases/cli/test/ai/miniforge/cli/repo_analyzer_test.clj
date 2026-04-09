;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.repo-analyzer-test
  "Tests for repository analysis — language, framework, and pack detection."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.repo-analyzer :as sut]))

(deftest detect-languages-test
  (testing "detects Clojure in miniforge repo"
    (let [langs (sut/detect-languages ".")]
      (is (contains? langs :clojure)))))

(deftest detect-frameworks-test
  (testing "detects Polylith in miniforge repo"
    (let [frameworks (sut/detect-frameworks ".")]
      (is (contains? frameworks :polylith)))))

(deftest detect-build-systems-test
  (testing "detects deps.edn in miniforge repo"
    (let [builds (sut/detect-build-systems ".")]
      (is (contains? builds :deps-edn))
      (is (contains? builds :babashka)))))

(deftest select-packs-test
  (testing "always includes foundations"
    (is (some #{"foundations-1.0.0"} (sut/select-packs #{} #{}))))

  (testing "adds terraform pack for terraform repos"
    (is (some #{"terraform-aws-1.0.0"} (sut/select-packs #{:terraform} #{}))))

  (testing "adds kubernetes pack for k8s frameworks"
    (is (some #{"kubernetes-1.0.0"} (sut/select-packs #{} #{:kubernetes})))))

(deftest analyze-repo-test
  (testing "full analysis returns all fields"
    (let [result (sut/analyze-repo ".")]
      (is (set? (:languages result)))
      (is (set? (:frameworks result)))
      (is (set? (:build-systems result)))
      (is (vector? (:packs result)))
      (is (seq (:packs result))))))
