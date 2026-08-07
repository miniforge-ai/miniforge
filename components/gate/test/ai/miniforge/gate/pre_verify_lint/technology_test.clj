;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.gate.pre-verify-lint.technology-test
  "Tests for pre-verify lint's technology detection."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.gate.pre-verify-lint.technology :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} detect-technologies-test
  (testing "detects Clojure from file extensions"
    (let [artifact {:code/files [{:path "src/core.clj" :action :create}]}]
      (is (contains? (sut/detect-technologies artifact) :clojure))))

  (testing "detects multiple languages"
    (let [artifact {:code/files [{:path "src/main.rs" :action :create}
                                 {:path "src/app.py" :action :create}]}]
      (is (contains? (sut/detect-technologies artifact) :rust))
      (is (contains? (sut/detect-technologies artifact) :python))))

  (testing "returns empty for no files"
    (is (empty? (sut/detect-technologies {:code/files []})))))
