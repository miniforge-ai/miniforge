;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0
(ns ai.miniforge.gate.pre-verify-lint
  "Pre-verify lint gate — runs language-specific linters before test execution.

   Catches syntax and dependency errors in seconds instead of waiting
   for minutes of JVM startup + test execution. Uses the same linter
   configuration from tech-fingerprints.edn as `bb miniforge scan`.

   Technology detection lives in `pre-verify-lint.technology`; linter
   execution and error-shape translation live in
   `pre-verify-lint.execution`. This file only wires the gate into the
   registry.

   Layer 0: Gate registration"
  (:require
   [ai.miniforge.gate.pre-verify-lint.execution :as execution]
   [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(defmethod ^{:stratum 0} registry/get-gate :pre-verify-lint
  [_]
  {:name        :pre-verify-lint
   :description "Run language linters before test execution to catch errors fast"
   :check       execution/check-pre-verify-lint
   :repair      execution/repair-pre-verify-lint})

;; Gate registration
(registry/register-gate! :pre-verify-lint)
