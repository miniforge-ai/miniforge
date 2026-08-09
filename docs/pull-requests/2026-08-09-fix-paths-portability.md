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
   entry in `$PATH` (`.`, `bin`, `../tools`) therefore produced a
   relative result, which breaks once the runner executes the command
   from a worktree with a different working directory than the JVM.

## Changes in Detail

- `split-path-entries` (Layer 0) splits a raw PATH string on
  `(Pattern/quote File/pathSeparator)`; `path-entries` (Layer 1) is now
  just the environment read. The split becoming a pure function is what
  makes the separator behaviour testable without mutating the JVM
  environment.
- `matching-command-path` is gone. The PATH scan in
  `resolve-cli-command-path` now goes through `normalize-command-path`,
  the same function the direct-path and `fs/which` branches already
  used, so every branch absolutizes and the docstring's contract holds
  everywhere. Removing the helper also keeps the file inside the
  3-layer budget (SL003) — routing `matching-command-path` through
  `normalize-command-path` at the same stratum would have pushed the
  namespace to 4 layers.
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

Regression check: reverting the absolutize fix makes
`test-path-scan-fallback-returns-an-absolute-path` fail as expected.
The separator test cannot fail on a POSIX runner — `File/pathSeparator`
*is* `":"` there — so on Linux CI it pins intent rather than catching a
revert; it becomes a live regression detector on Windows.

## Deployment Plan

No migration. Internal helper, no public API or wire-format change.

## Related Issues/PRs

- #1662 — the move-only split that surfaced both comments.

## Checklist

- [x] `bb lint:clj` clean
- [x] `bb lint:stratum` clean (3 layers)
- [x] New tests green, targeted regression verified
