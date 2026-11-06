<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(compliance-scanner): turn signing off per commit, not in the repository's config

## Overview

`execute!` (the compliance scanner's fix-and-PR step) ran
`git config commit.gpgsign false` in the repository it was given, then
committed. That write persists. The scanner runs in the user's own
checkout, whose config file every linked worktree shares, so every later
commit in that checkout and its worktrees went out unsigned.

The commit now passes `-c commit.gpgsign=false` instead. Signing is off
for the scanner's own commit and nowhere else.

## Motivation

The developer checkout's shared `.git/config` was found carrying
`commit.gpgsign = false`, next to the `Isolation Test` identity that the
workflow isolation fixture wrote (fixed in #2005 and #2013). The scanner
is the other writer of that key, and the only non-test code in the
repository that writes a persistent git setting into a repository it did
not create. The checkout holds scanner branches from 2026-05-01 and
2026-07-18 (`fix/compliance-210-…`, `fix/compliance-810-…`), so it has run
there. Without this change, removing the key by hand lasts only until the
next `mf scan --execute`.

## Changes in Detail

1. `components/compliance-scanner/src/.../execute.clj`: the
   `git config commit.gpgsign false` call is gone; the commit runs as
   `git -c commit.gpgsign=false commit --no-verify -m …`. The reason for
   turning signing off (the 1Password agent blocks in subprocess contexts)
   is unchanged.
2. `components/compliance-scanner/test/.../execute_repo_config_test.clj`
   (new): drives `execute!` through the component interface against a
   throwaway repository with a bare origin.
   - Every git command, the scanner's included, runs with a HOME and global
     config under the temp root and no other `GIT_*` variable, so nothing
     reaches the developer's checkout even under a git hook.
   - `gh` is stubbed.
   - The global config demands signing through `gpg.program = false`, so
     the scanner's commit lands only if signing is off for it.
   - The plan comes from the scanner's own `->violation` and `plan`, the
     shape `execute!` receives in production.

## Testing Plan

1. New test: 1 test, 3 assertions, passing. Asserts the patched file
   reaches origin on the scanner's branch, the stubbed PR URL comes back,
   and the repository's config settings (all but `branch.*`, which
   `git checkout -b <branch> origin/main` writes for upstream tracking)
   are the same after the run as before.
2. Against the old `execute.clj`: the config assertion fails, showing
   `commit.gpgsign=false` added to the repository's config.
3. `execute-test` (existing): passing.
4. clj-kondo on both changed files: 0 errors, 0 warnings.

## Deployment Plan

No migration. A checkout the scanner already ran in keeps
`commit.gpgsign = false` in its config until removed by hand:

```bash
git config --local --unset commit.gpgsign
```

## Related Issues/PRs

1. #2005: the workflow isolation fixture can no longer write to the launch
   repository's config (the `Isolation Test` identity).
2. #2013: test launchers strip every repository-binding git variable.

## Checklist

- [x] Persistent config write replaced with a per-command `-c`
- [x] Regression test fails on the old code, passes on the new
- [x] Test runs hermetically: own HOME, own global config, no inherited
      `GIT_*`
- [x] PR doc
