<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Add shared `bb ccov` coverage support and bootstrap prefetch

## Summary

Add a shared Babashka coverage task backed by `bb-test-runner`, expose it
as `bb ccov`, and make repo bootstrap prefetch the Cloverage tool so the
first coverage run does not have to install tooling on demand.

## Changes

- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.clj`
  - add coverage command derivation and execution helpers
- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/interface.clj`
  - expose pure helpers plus `install-coverage-tool` and `run-coverage`
- `components/bb-test-runner/test/ai/miniforge/bb_test_runner/core_test.clj`
  - add coverage-path, deps-merge, and Cloverage argv tests
- `bb.edn`
  - add `bb ccov`
  - add `bb install:coverage`
  - make `bb bootstrap` prefetch the coverage tool
- `README.md`
  - document bootstrap coverage prefetch
- `docs/quickstart.md`
  - document bootstrap coverage prefetch
- `CONTRIBUTING.md`
  - standardize coverage command on `bb ccov`

## Verification

- `git diff --check`
- `bb install:coverage`
- `bb bootstrap`
- `clojure -Sdeps '{:deps {ai.miniforge/bb-test-runner {:local/root "components/bb-test-runner"} io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"} babashka/fs {:mvn/version "0.5.22"}} :paths ["components/bb-test-runner/src" "components/bb-test-runner/test"]}' -M -m cognitect.test-runner -d components/bb-test-runner/test`

Results:
- `bb install:coverage` passed
- `bb bootstrap` passed
- `bb-test-runner` component tests passed: 11 tests, 26 assertions

Note:
- `bb ccov` was started against the full monorepo but did not complete in
  a reasonable interactive window, so this PR does not claim a completed
  full-repo coverage run yet.
