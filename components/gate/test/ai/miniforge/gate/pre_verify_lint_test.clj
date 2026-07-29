;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.gate.pre-verify-lint-test
  "Tests for the pre-verify lint gate's registration and wiring.

   Check/repair behavior lives in `pre-verify-lint.execution-test`; technology
   detection behavior lives in `pre-verify-lint.technology-test`. This file
   only confirms the gate is wired correctly under `:pre-verify-lint`."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.gate.pre-verify-lint]
   [ai.miniforge.gate.pre-verify-lint.execution :as execution]
   [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} pre-verify-lint-registered-test
  (testing "the gate registers under :pre-verify-lint"
    (is (contains? (registry/list-gates) :pre-verify-lint))))

(deftest ^{:stratum 0} pre-verify-lint-wires-execution-fns-test
  (testing "get-gate resolves to execution's check/repair functions"
    (let [gate (registry/get-gate :pre-verify-lint)]
      (is (= :pre-verify-lint (:name gate)))
      (is (= execution/check-pre-verify-lint (:check gate)))
      (is (= execution/repair-pre-verify-lint (:repair gate))))))
