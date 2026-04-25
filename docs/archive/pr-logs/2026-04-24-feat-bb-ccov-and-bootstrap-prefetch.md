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

This PR now also lifts the coverage task onto a repo-local developer tool
catalog so `bb-utils` can run language-specific toolsets instead of
hardcoding one JVM-only coverage tool.

## Changes

- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.clj`
  - add coverage command derivation and execution helpers
- `components/bb-test-runner/src/ai/miniforge/bb_test_runner/interface.clj`
  - expose pure helpers plus `install-coverage-tool` and `run-coverage`
- `components/bb-test-runner/test/ai/miniforge/bb_test_runner/core_test.clj`
  - add coverage-path, deps-merge, and Cloverage argv tests
- `components/bb-dev-tools/*`
  - add a repo-local dev-tool catalog and toolset runner
  - support adapter-driven tools like `:clojure/cloverage`
  - support generic external command tools for non-Clojure repos
- `bb.edn`
  - route `bb ccov` through the repo toolset
  - route `bb install:coverage` through the repo toolset
  - keep `bb bootstrap` prefetching the configured coverage toolset
- `bb-tools.edn`
  - define the repo coverage toolset as data
- `README.md`
  - document bootstrap coverage prefetch
- `docs/quickstart.md`
  - document bootstrap coverage prefetch
- `CONTRIBUTING.md`
  - standardize coverage command on `bb ccov`
- `components/web-dashboard/src/ai/miniforge/web_dashboard/views.clj`
  - split the large layout renderer into smaller helpers
  - move layout copy to localized message lookups
- `components/web-dashboard/resources/config/web-dashboard/messages/en-US.edn`
  - add localized layout strings
- `components/web-dashboard/test/ai/miniforge/web_dashboard/views_test.clj`
  - add focused layout contract coverage

## Verification

- `git diff --check`
- `bb install:coverage`
- `bb bootstrap`
- `clojure -Sdeps '{:deps {ai.miniforge/bb-test-runner {:local/root "components/bb-test-runner"} io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"} babashka/fs {:mvn/version "0.5.22"}} :paths ["components/bb-test-runner/src" "components/bb-test-runner/test"]}' -M -m cognitect.test-runner -d components/bb-test-runner/test`
- `clojure -Sdeps '{:deps {ai.miniforge/bb-dev-tools {:local/root "components/bb-dev-tools"} io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"} babashka/fs {:mvn/version "0.5.22"} ai.miniforge/bb-paths {:local/root "components/bb-paths"} ai.miniforge/bb-test-runner {:local/root "components/bb-test-runner"}} :paths ["components/bb-dev-tools/src" "components/bb-dev-tools/test" "components/bb-test-runner/src" "components/bb-paths/src"]}' -M -m cognitect.test-runner -d components/bb-dev-tools/test`
- `clojure -M:test -e "(require 'ai.miniforge.web-dashboard.views-test)(let [result (clojure.test/run-tests 'ai.miniforge.web-dashboard.views-test)] (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))"`
- `bb ccov`

Results:
- `bb install:coverage` passed
- `bb bootstrap` passed
- `bb-test-runner` component tests passed: 11 tests, 26 assertions
- `bb-dev-tools` component tests passed: 4 tests, 12 assertions
- `web-dashboard.views-test` passed: 5 tests, 18 assertions

Note:
- the original Cloverage blocker in `web_dashboard/views.clj` is fixed;
  the next full-repo coverage blocker is now
  `components/cli-web/src/ai/miniforge/cli/web/components.clj`, which
  fails instrumentation with the same JVM `Method code too large!` limit.
