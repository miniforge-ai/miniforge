;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.gate.pre-verify-lint-test
  "Tests for the pre-verify lint gate."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.gate.pre-verify-lint :as sut]))

;; Access private functions
(def detect-technologies (var-get #'sut/detect-technologies))

(deftest detect-technologies-test
  (testing "detects Clojure from file extensions"
    (let [artifact {:code/files [{:path "src/core.clj" :action :create}]}]
      (is (contains? (detect-technologies artifact) :clojure))))

  (testing "detects multiple languages"
    (let [artifact {:code/files [{:path "src/main.rs" :action :create}
                                 {:path "src/app.py" :action :create}]}]
      (is (contains? (detect-technologies artifact) :rust))
      (is (contains? (detect-technologies artifact) :python))))

  (testing "returns empty for no files"
    (is (empty? (detect-technologies {:code/files []})))))

(deftest check-pre-verify-lint-no-linter-test
  (testing "passes when linter not available"
    (let [result (sut/check-pre-verify-lint {} {})]
      (is (true? (:passed? result))))))

(deftest repair-always-fails-test
  (testing "repair returns failure — lint errors need agent"
    (let [result (sut/repair-pre-verify-lint {} [{:type :lint-error}] {})]
      (is (false? (:success? result))))))
