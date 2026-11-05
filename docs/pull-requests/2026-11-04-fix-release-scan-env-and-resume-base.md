<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(release): the release scan ignores a hook's GIT_DIR and measures a resume from its original base

## Overview

Two defects in the release skip probe, both raised by Copilot on #2264 and both
present on `main`:

1. The host git calls of the release scan inherit `GIT_DIR`, `GIT_WORK_TREE`
   and `GIT_INDEX_FILE` when miniforge runs from a git hook, so they read the
   hook's repository instead of the task worktree.
2. A resumed run whose earlier attempt recorded no task base measures its
   committed work from the checkpoint it restored, finds nothing, and skips
   the release, so the earlier attempt's commits are never published.

## Motivation

1. `release-scan/host-runner` and `release-worktree/git-dirty-files` ran
   `clojure.java.shell/sh` with `:dir` only. `:dir` does not override the
   repository variables a hook exports. The release executor's own git calls
   already start from `release-executor/local-git-env` for this reason.
2. On such a resume, implement records the candidates
   `implement/base-ref-candidates` returns: `X...HEAD` ranges from the
   original commit and branch, then the resumed environment's `:base-sha`.
   That sha is the checkpoint, which already holds the earlier commits.
   `release-base-shas` kept only commit shas, so it dropped the ranges and
   diffed from the checkpoint.

## Changes in Detail

1. `release-scan/host-runner` and `release-worktree/git-dirty-files` pass
   `:env (release-executor/local-git-env)`.
2. `release-worktree/release-bases` (replaces `release-base-shas`) returns the
   bases in order: a recorded commit sha as `{:sha s}`, a recorded `X...HEAD`
   range as `{:merge-base X}`. On a resume the environment's `:base-sha` (the
   checkpoint) is not a base, and when the recorded refs came from a resume
   (they carry ranges) their one commit, which is that checkpoint, is not
   either. On a first attempt the environment's base commit still follows the
   recorded refs.
3. `release-worktree/base-shas` resolves them with the scan's runner: a merge
   base is `git merge-base X HEAD`; a ref that names no commit here, or shares
   no history with HEAD, is left out; a git failure is the scan's
   `:release/git-status-failed` anomaly. `committed-since-base` diffs from the
   first that resolves, as before.
4. `release-scan/missing-commit?` becomes public, so the merge-base lookup
   reads a missing ref the same way `base-diff` does. It also decides "no
   shared history" for `git merge-base`: only an exit 1 that printed nothing
   on stdout or stderr. An exit 1 with stderr (a capsule whose container is
   gone) is the scan's `:release/git-status-failed` anomaly, not unrelated
   history, so the release cannot skip committed work on it (Copilot review).

## Testing Plan

1. `release_scan_git_env_test.clj`: the process environment is a git hook's
   in another repository (`GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`), and a
   child started without `:env` inherits it. `git-dirty-files` lists the
   worktree's untracked file, and the host runner's `rev-parse
   --show-toplevel` and `status` resolve the worktree. On the base branch both
   tests fail: the scan lists the hook repository instead.
2. `release_resume_base_test.clj`: a clean worktree whose HEAD (the
   checkpoint) holds an earlier attempt's commit; the task base refs are
   `implement/base-ref-candidates`' real output for that resume. The release
   prepares `src/feature.clj` instead of skipping. On the base branch it
   skips. `merge-base-error-is-a-failure-not-unrelated-history-test` runs
   `merge-base-sha` with runner results built by `release-scan/command-failure`:
   exit 1 with nothing printed is nil, exit 1 with stderr is the anomaly. The
   second case fails on the code before the fix.
3. The release, file-scan, committed-work and checking-commits tests, and the
   project tests that run the release phase.

## Deployment Plan

No migration. Takes effect with the next CLI build.

## Related Issues/PRs

Builds on #2396 (the release.clj split, merged as 453eecc6f). Answers the #2264 Copilot threads
PRRT_kwDOQ8eOgc6x2sTk (host probes inherit GIT_DIR / GIT_WORK_TREE) and
PRRT_kwDOQ8eOgc6x3EXj (a resume with no recorded task base scans from the
checkpoint). #2264's own probe, which those threads were on, is superseded
by the probe from #2305 and is being removed from #2264.

## Checklist

- [x] Regression tests fail on the base branch
- [ ] Adversarial review and pinned-standards review
- [ ] CI green, Copilot comments resolved
