<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(cli): platform PATH separator and absolute PATH-scan results

## Overview

Two portability defects in `ai.miniforge.cli.workflow-runner.paths`, both
flagged by Copilot on #1662 and deferred there because that PR was a
move-only namespace split.

## Motivation

1. `path-entries` split `$PATH` on a hard-coded `":"`. On Windows the
   separator is `";"`, so the whole PATH parsed as a single bogus entry
   and the CLI-resolution fallback silently found nothing.
2. `resolve-cli-command-path` documents an absolute-path contract, but
   the PATH-scan fallback returned `entry/cmd` verbatim. A relative
   entry in `$PATH` (`.`, `bin`, `../tools`) therefore produced a relative result.
   That breaks when the runner's worktree differs from the JVM's working directory.

## Changes in Detail

- `split-path-entries` (Layer 0) splits a raw PATH string on
  `(Pattern/quote File/pathSeparator)`; `path-entries` (Layer 1) is now
  just the environment read. The split becoming a pure function is what
  makes the separator behaviour testable without mutating the JVM
  environment.
- `matching-command-path` is gone. The PATH scan in
  `resolve-cli-command-path` now uses `normalize-command-path`, matching the
  direct-path and `fs/which` branches. Every branch now fulfills the absolute-path
  contract. Removing the helper also preserves the 3-layer budget (SL003).
  Routing `matching-command-path` through `normalize-command-path` would have
  pushed the namespace to 4 layers.
- Docstring updated to state the absolutize guarantee explicitly.

Behaviour note: results are absolutized, not canonicalized, matching
what the `fs/which` branch already did — symlinks are preserved.

## Testing Plan

New `bases/cli/test/ai/miniforge/cli/workflow_runner/paths_test.clj`:

- `test-path-splitting-honours-the-platform-separator` — entries joined
  with `File/pathSeparator` split apart; an entry containing the *other*
  platform's separator stays whole; a nil PATH does not NPE.
- `test-path-scan-fallback-returns-an-absolute-path` — an executable in
  a temp dir reached via a relative PATH entry resolves to an absolute
  path pointing at that executable.

Run: `clojure -M:dev:test` over `paths-test` and `preflight-test`.
`paths-test` is 2 tests / 6 assertions, green. `preflight-test` has one
pre-existing unrelated failure on main (the codex generic-path
assertion, being fixed separately).

The temp fixture uses `File/setExecutable` on Windows and POSIX permission
bits elsewhere. It deletes its temp dir in `finally`; review raised both points.

Regression check: reverting the absolutize fix makes
`test-path-scan-fallback-returns-an-absolute-path` fail as expected.
The separator test cannot fail on POSIX: `File/pathSeparator` *is* `":"` there.
On Linux CI it pins intent, not reversion detection. It detects regressions on Windows.

## Deployment Plan

No migration. Internal helper, no public API or wire-format change.

## Related Issues/PRs

PR #1662 is the move-only split that surfaced both comments.

## Checklist

- [x] `bb lint:clj` clean
- [x] `bb lint:stratum` clean (3 layers)
- [x] New tests green, targeted regression verified
