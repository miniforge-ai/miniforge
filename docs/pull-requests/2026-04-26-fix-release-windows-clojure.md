<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# fix/release-windows-clojure

## Summary

Fixes the Windows release build by making Babashka task subprocesses resolve a
real Clojure executable and by installing the same `deps.clj` shim in the
release workflow that CI already uses on Windows.

Without this, `bb build:cli` on GitHub-hosted Windows runners shells out via
Java `ProcessBuilder`, which cannot resolve the PowerShell-module-only Clojure
installation from `setup-clojure`.

## Key Changes

- added portable Clojure executable resolution helpers to
  [core.clj](/tmp/mf-fix-release-windows/components/bb-proc/src/ai/miniforge/bb_proc/core.clj)
  and
  [interface.clj](/tmp/mf-fix-release-windows/components/bb-proc/src/ai/miniforge/bb_proc/interface.clj)
- updated Babashka task entry points in
  [build.clj](/tmp/mf-fix-release-windows/tasks/build.clj),
  [test_runner.clj](/tmp/mf-fix-release-windows/tasks/test_runner.clj),
  [standards.clj](/tmp/mf-fix-release-windows/tasks/standards.clj), and
  [bb.edn](/tmp/mf-fix-release-windows/bb.edn)
  to use the resolved Clojure command instead of the hard-coded `clojure`
  token
- added the Windows `deps.clj` shim install step to
  [release.yml](/tmp/mf-fix-release-windows/.github/workflows/release.yml)
- added unit coverage for the new resolver behavior in
  [core_test.clj](/tmp/mf-fix-release-windows/components/bb-proc/test/ai/miniforge/bb_proc/core_test.clj)

## Validation

- `clj-kondo --lint tasks/build.clj tasks/test_runner.clj
  tasks/standards.clj
  components/bb-proc/src/ai/miniforge/bb_proc/core.clj
  components/bb-proc/src/ai/miniforge/bb_proc/interface.clj
  components/bb-proc/test/ai/miniforge/bb_proc/core_test.clj`
- `clojure -M:dev:test -e "(require 'clojure.test
  'ai.miniforge.bb-proc.core-test) (let [r
  (clojure.test/run-tests 'ai.miniforge.bb-proc.core-test)] (System/exit (if
  (zero? (+ (:fail r 0) (:error r 0))) 0 1)))"`
- `bb build:cli`
- `bb pre-commit`
