<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Scan the capsule checkout in governed releases; review fixes for #2305

## Overview

Follow-up to #2305, from its post-merge adversarial and rules reviews (#2305
merged without them). In a governed run, release's git scans now run in the
capsule's own checkout instead of the host worktree path, which does not exist
inside the capsule. The rest of the PR fixes what the reviews found in the
release scan code.

## Motivation

The reviews found:

- **High (governed runs):** the capsule scans, both the committed scan #2305
  added and the existing `git-dirty-files-capsule`, passed the host worktree
  path (`:execution/worktree-path`) as the capsule workdir. Inside the capsule
  the checkout is `:execution/capsule-workdir` (#2240 moved implement, persist
  and the `:no-secrets` gate to it). The scan could not run there, so a
  governed `:already-implemented` release never found its committed work.
- **Must-fix (dewey 050):** "resolves outside the worktree" was an inline
  user-facing string.
- **Low:** `skip-probe` threw a failed scan even when implement's recorded
  files would carry the release; the branch's commits ship either way, and the
  files feed only PR metadata. Throwing there also goes against dewey 005
  (throw only at boundaries).
- **Low:** SHA-256 repositories (64-hex shas) fell back to the old skip; a
  capsule `rev-parse` exiting 1 with an error (container gone) read as "commit
  not here" and skipped.
- **Should-fix:** private alias shims in `release.clj` (dewey 008); a second
  capsule git runner, three copies of the governed-mode check, and four
  hand-built exception-to-failure maps (dewey 002, 003); `release_scan.clj`
  over the 3-layer budget (dewey 210); undocumented defs and a stale
  `build-workflow-state` docstring (dewey 006, 009); `(or (get-in ..) "")`
  instead of a `get-in` default (dewey 210).
- #2305's PR doc lacked Deployment Plan and Related Issues/PRs and had stale
  statements (dewey 721).

## Changes in Detail

1. `release.clj`:
   - `capsule-target`: the governed run's executor, environment and
     `:workdir` (`:execution/capsule-workdir`, else the worktree path), or nil.
     `skip-probe`, `build-workflow-state` and `committed-since-base` use it.
   - `git-dirty-files-capsule` uses `release-scan`'s capsule runner and
     reader; `porcelain-scan-entry` maps a `git status` pair to a scan entry.
   - `committed-since-base` composes `release-scan/first-base-diff` and
     `read-entries`.
   - `skip-probe` returns a failed scan as its anomaly again;
     `build-workflow-state` raises it unless implement's recorded files carry
     the release.
   - The `command-failure` / `git-status-failed` aliases are gone; call sites
     use `release-scan/...`.
   - `commit-sha-pattern` accepts 7 to 64 hex digits, with a docstring.
2. `release_scan.clj`:
   - `exception-failure`: one factory for a thrown exception's failure.
   - `capsule-runner` quotes only arguments that are not shell-safe, so plain
     git commands reach `sh -c` unchanged.
   - `missing-commit?`: exit 1 with no output; an exit 1 that printed an
     error is a failure.
   - `read-entry`: an entry with no mode (from `git status`) is read as a
     file.
   - Layers 0–2 only: `first-base-diff` and `read-entries` at Layer 2;
     internals are private.
3. `messages/en-US.edn`: `:release/path-outside-worktree`.
4. `docs/pull-requests/2026-11-01-fix-release-skip-probe-committed.md`: the
   missing sections, and corrected statements.

## Testing Plan

`release_committed_work_test.clj`:

1. A governed run scans `:execution/capsule-workdir`: every capsule call
   (`git status`, `rev-parse`, `diff`) runs there, and a 64-hex base sha is
   kept.
2. A capsule `rev-parse` exiting 1 with an error raises
   `:release/git-status-failed` instead of skipping.
3. When the committed scan fails but implement recorded files, release
   prepares with the recorded files (replaces the test that expected a raise).
4. The host reader refuses `../outside.clj`.

The existing capsule tests in `release_file_scan_test.clj` cover the rewritten
`git-dirty-files-capsule`: an executor error, a non-zero exit, a failed `cat`,
a thrown executor, listed files, and paths a shell would mangle.

## Deployment Plan

No configuration or migration.

## Related Issues/PRs

- #2305 (the change this follows up), #2240 (capsule workdir)

## Not in this PR

- A phase boundary commits everything after each phase, so files verify or
  review leave behind now count as committed work and can stop an
  `:already-implemented` release from skipping. Whether release should skip
  those is a product decision, raised with the operator.
- `error-release` reads `[:phase :iterations]`, which `begin-phase-attempt`
  drops each attempt, so retries of a failed scan are bounded only by the
  runner's stuck-phase guard (pre-existing).
- Moving the tests that create real git repositories to a project test
  directory (dewey 310): sibling release tests already do the same in the
  component.

## Checklist

- [x] Regression tests for each behaviour change
- [x] User-facing strings in the messages catalog
- [x] Stratum metadata consistent in edited namespaces
