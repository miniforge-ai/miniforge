;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.gate.pre-verify-lint-test
  "Tests for the pre-verify lint gate.

   Technology-detection behavior lives in
   `pre-verify-lint.technology-test`."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.gate.pre-verify-lint :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} check-pre-verify-lint-no-linter-test
  (testing "passes when linter not available"
    (let [result (sut/check-pre-verify-lint {} {})]
      (is (true? (:passed? result))))))

(deftest ^{:stratum 0} repair-always-fails-test
  (testing "repair returns failure — lint errors need agent"
    (let [result (sut/repair-pre-verify-lint {} [{:type :lint-error}] {})]
      (is (false? (:success? result))))))
