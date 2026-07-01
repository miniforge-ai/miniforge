<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Workflow Resume Anomaly Returns

## Summary

Convert workflow-resume validation and expected not-found paths from thrown
exceptions to canonical anomaly data.

## Changes

- Return `:invalid-input` anomalies from workflow-resume schema validation.
- Return `:not-found` anomalies for missing resume event sources and unresolved
  workflow identities.
- Update CLI resume/status adapters to re-escalate returned anomalies at the
  user boundary.
- Update workflow-resume regressions to assert returned anomaly maps.

## Validation

- `clojure -M:dev:test` for `ai.miniforge.workflow-resume.core-test`,
  `ai.miniforge.cli.main.commands.resume-test`, and `ai.miniforge.cli.main-test`
- Scoped exceptions-as-data scanner on touched source files:
  `{:cleanup-needed 0, :fatal-only 0}`
- `clj-kondo --lint` on changed workflow-resume/CLI source and test files
