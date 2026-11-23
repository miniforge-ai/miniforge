<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Scan the capsule checkout in governed releases; review fixes for #2305

## Overview

Follow-up to #2305, from its post-merge adversarial and rules reviews (#2305
merged without them). Governed release scans now run in the capsule's own
checkout. The host worktree path does not exist inside the capsule.
The rest of the PR fixes what the reviews found in the
release scan code.

## Motivation

The reviews found:

- **High (governed runs):** capsule scans passed `:execution/worktree-path` as
  their workdir. This affected the committed scan and `git-dirty-files-capsule`.
  Inside the capsule
  the checkout is `:execution/capsule-workdir` (#2240 moved implement, persist
  and the `:no-secrets` gate to it). The scan could not run there, so a
  governed `:already-implemented` release never found its committed work.
- **Must-fix (dewey 050):** "resolves outside the worktree" and
  "<command> exited <n>" were inline user-facing strings.
- **Should-fix (moved code, dewey 720 obligation 6):** the dirty scan followed
  symlinks. The capsule `cat` and host `.isFile` + `slurp` read the target into
  PR metadata, even outside the worktree. The scan now uses `release-scan`'s readers.
- **Low:** `skip-probe` threw failed scans even when recorded files could carry
  the release. The branch's commits ship either way; files feed PR metadata.
  Throwing there also goes against dewey 005
  (throw only at boundaries).
- **Low:** SHA-256 repositories (64-hex shas) fell back to the old skip.
  A capsule `rev-parse` exit 1 could mean an execution error, such as a missing
  container. The scan treated it as an absent commit and skipped.
- **Should-fix:** `release.clj` had private alias shims (dewey 008).
  It duplicated the capsule runner, governed-mode checks and exception-to-failure
  maps (dewey 002, 003). `release_scan.clj` exceeded three layers (dewey 210).
  Undocumented defs and a stale `build-workflow-state` docstring violated dewey
  006 and 009. A `get-in` fallback also needed its default argument (dewey 210).
- #2305's PR doc lacked Deployment Plan and Related Issues/PRs and had stale
  statements (dewey 721).

## Changes in Detail

1. `release.clj`:
   - `capsule-target`: the governed run's executor, environment and
     `:workdir` (`:execution/capsule-workdir`, else the worktree path), or nil.
   - `scan-io`: the runner, reader and root a scan uses, host or capsule.
   - `dirty-scan`: the one place that picks the host or capsule dirty scan;
     `skip-probe` and `build-workflow-state` both use it.
   - `git-dirty-files-capsule` uses `release-scan`'s capsule runner and
     reader; `porcelain-scan-entry` maps a `git status` pair to a scan entry.
   - `git-dirty-files` preserves a symlink's path and action with empty content,
     never reading its target. That path can be the only change.
     Failures use `release-scan/exception-failure`.
   - `committed-since-base` composes `release-scan/first-base-diff` and
     `read-entries` through `scan-io`.
   - `skip-probe` returns a failed scan as its anomaly again;
     `build-workflow-state` raises it unless implement's recorded files carry
     the release.
   - The `command-failure` / `git-status-failed` aliases are gone; call sites
     use `release-scan/...`.
   - `commit-sha-pattern` accepts 7 to 64 hex digits, with a docstring.
   - Strata follow references to defs as well as calls (as `stratum-lint`
     counts them): `release-base-shas` is stratum 1, `committed-since-base`
     and `dirty-scan` 2, `skip-probe` 3.
2. `release_scan.clj`:
   - `exception-failure`: one factory for a thrown exception's failure.
   - `capsule-runner` quotes only arguments that are not shell-safe, so plain
     git commands reach `sh -c` unchanged.
   - `capsule-reader` returns empty content for symlinks and existing nonregular
     paths. It reads other paths with `cat`; missing or inaccessible paths fail
     and are retried.
   - `missing-commit?`: exit 1 with no output; an exit 1 that printed an
     error is a failure.
   - `read-entry`: an entry with no mode (from `git status`) is passed to the
     reader, which refuses symlinks.
   - Layers 0–2 only, counting references to defs: `capsule-runner`,
     `first-base-diff` and `read-entries` at Layer 2; internals are private.
3. `messages/en-US.edn`: `:release/path-outside-worktree`,
   `:release/command-exited`.
4. `docs/pull-requests/2026-11-01-fix-release-skip-probe-committed.md`: the
   missing sections, and corrected statements.

## Testing Plan

The authoring cloud session could not fetch Clojars dependencies, so it relied
on CI. Every edited file was checked with the Clojure reader.
The capsule reader's shell guard was tested under real `sh` against regular
files, symlinks and directories.

`release_committed_work_test.clj`:

1. A governed run scans `:execution/capsule-workdir`: every capsule call
   (`git status`, `rev-parse`, `diff`) runs there, and a 64-hex base sha is
   kept.
2. A capsule `rev-parse` exiting 1 with an error raises
   `:release/git-status-failed` instead of skipping.
3. When the committed scan fails but implement recorded files, release
   prepares with the recorded files (replaces the test that expected a raise).
4. The host reader refuses `../outside.clj`.
5. A governed release with implement `:success` lists its dirty files in the
   capsule checkout (the `build-workflow-state` path).
6. An untracked symlink outside the worktree is released by path with empty
   content, even as the sole change. Host and real `sh -c` capsule cases verify
   that its secret target never enters the release.

Existing capsule tests cover the rewritten `git-dirty-files-capsule` in
`release_file_scan_test.clj`. Cases include executor errors, nonzero exits,
failed reads, listed files and shell-sensitive paths. Their stub key
for the reader moves from `"cat"` to `"if ["`, the guarded command's prefix.

## Deployment Plan

No configuration or migration.

## Related Issues/PRs

This follows #2305 and uses the capsule workdir introduced in #2240.

## Not in this PR

- A phase boundary commits everything after each phase. Files left by verify
  or review now count as committed work and can prevent an
  `:already-implemented` release from skipping. Whether release should skip
  those is a product decision, raised with the operator.
- `error-release` reads `[:phase :iterations]`, which `begin-phase-attempt`
  drops each attempt, so retries of a failed scan are bounded only by the
  runner's stuck-phase guard (pre-existing).
- Moving the tests that create real git repositories to a project test
  directory (dewey 310): sibling release tests already do the same in the
  component.
- Runner and reader result factories remain outside this PR (dewey 003).
  Their `{:failure ...}` / `{:content ...}` maps and executor-result access
  need shared helpers. The shapes came with #2305; changing them
  touches every scan.
- The authoring cloud session had no pre-commit hooks. Its commits did not
  run `bb commit-budget` (200 lines per commit). The PR remained within the
  600-line `bb pr-budget` CI gate.

## Checklist

- [x] Regression tests for each behaviour change
- [x] User-facing strings in the messages catalog
- [x] Stratum metadata consistent in edited namespaces
