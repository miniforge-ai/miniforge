<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: the isolation fixture cannot write to the launch repository's config

## Overview

The developer checkout's shared `.git/config` carried `user.name = Isolation Test` and
`user.email = isolation-test@example.invalid`. A repo-local identity overrides the global one in
every worktree. From 2026-09-03 to 2026-10-04, every commit made there without a per-command
identity override was authored as `Isolation Test`.

The writer is `init-host-repo!` in the isolation fixture, in the form it had before its first commit.
The code on `main` does not repeat the write under a hook. This PR closes the two gaps that were
left and adds the regression test the fixture never had.

## Motivation

### What happened

1. `runner-test` is in the pre-commit smoke set and registers `with-isolated-host`.
2. In a linked worktree, `git commit` hands its hooks `GIT_DIR=<common>/.git/worktrees/<name>`.
   git obeys `GIT_DIR` over `-C <dir>`.
3. The fixture's first draft ran `git -C <tmp> init`, then `git -C <tmp> config` for `user.email`,
   `user.name` and `commit.gpgsign`, with the hook's environment. All four acted on the launch
   repository. A linked worktree's config is the shared one, so every worktree got the result.
4. The PR that added the fixture (#1894) recorded only the `core.bare = true` half and repaired only
   that. The three `git config` writes stayed.

Evidence:

1. Reflogs under `.git/logs`: the last entry signed `Heimdall <heimdall@miniforge.ai>` is at
   2026-09-03 22:18:08 and the first signed `Isolation Test` at 22:22:43. The first commit with the
   test identity is the first commit of the #1894 branch, at 22:34:04.
2. Replayed in a scratch repository, from a real pre-commit hook in a linked worktree. The unscrubbed
   commands rewrote `core.bare`, `user.name`, `user.email` and `commit.gpgsign` in the scratch launch
   repository's config.
3. The fixture as it stands on `main`, run the same way, left that config byte-identical.

### What was still open

1. The scrub was a list of five names. `GIT_CONFIG`, which redirects a `git config` write to a named
   file, was not on it. With that variable inherited, the fixture on `main` still wrote `user.name`
   into another repository's config. No hook exports it, so this never fired.
2. The config writes named no file. Their target was whatever repository git resolved.
3. Nothing tested any of this. The scrub could be narrowed or removed with every suite still green.

## Changes in Detail

Both fixture twins change identically: `components/workflow/test/.../isolation_test_support.clj`
and `projects/miniforge/test/.../isolation_support.clj`.

1. `git!` takes the process environment as its first argument and drops every `GIT_*` variable from
   it before git starts. It scrubs whatever it is handed, so no caller can skip the step.
2. `config-args` builds `git config --file <dir>/.git/config <key> <value>` with an absolute path.
   A write that names its file cannot reach another repository, and fails when `<dir>` holds no
   repository.
3. `host-config` holds the three entries as data.
4. `init-host-repo!` gains a second arity that takes the process environment, so a test can hand it
   a hook's. The namespace stays at three strata.

One behaviour change follows from the prefix rule. The fixture's own git commands no longer see
`GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM` or `GIT_CONFIG_NOSYSTEM` when a launcher sets them. Nothing
in this repository or its CI sets them for a test JVM.

New tests, one per twin (`isolation_test_support_test.clj`, `isolation_support_test.clj`):

1. `test-git-drops-a-variable-no-list-would-name` aims `GIT_TRACE` at a file. The fixture's `git!`
   leaves no trace file. The same call unscrubbed writes one.
2. `test-fixture-under-hook-environment-leaves-launch-config-alone` builds a decoy launch repository
   with a linked worktree and its own identity. It runs `init-host-repo!` with a hook environment
   aimed at the decoy and compares the decoy's config file before and after.
3. `test-host-config-writes-name-their-own-file` replays the config writes with the hook environment
   unscrubbed and checks the decoy is unchanged. It then sends one write that names no file and
   checks it does reach the decoy. That shows the environment in use is one that redirects.

The tests lay their `GIT_*` variables over `PATH` and `HOME` alone. A call that passes them to git
unscrubbed can reach the decoy and nothing else, even when the test JVM itself runs under a hook.

`tasks/test_runner.clj` adds the project-level test to the `bb test:integration` list.

## Testing Plan

1. New tests, both twins: 3 tests, 6 assertions each, passing.
2. The brick tests run against six variants of the fixture, swapped in by `with-redefs`:
   1. As written: green.
   2. Scrub removed: 2 of 3 tests red.
   3. Scrub cut back to the five-name list: 1 red.
   4. Scrub cut to the 15 names `git rev-parse --local-env-vars` prints: 1 red.
   5. `--file` removed: 1 red.
   6. Five-name list and no `--file`, which is the code on `main`: 3 red.
3. The changed fixture and its tests, run from a real pre-commit hook in a scratch linked worktree:
   passing, scratch launch config byte-identical.
4. Workflow brick namespaces that register the fixture, run directly: 116 tests, 0 failures.
5. Project namespaces that register the twin (`dag-orchestrator-test`, `runner-integration-test`,
   `opsv-lifecycle-integration-test`) plus the new test, run through the project `deps.edn`:
   62 tests, 0 failures.

## Deployment Plan

Test code only; nothing ships. A checkout that already carries the damage needs a one-time repair,
run in that checkout. If it had its own local identity before, set those values back. Otherwise
remove the two keys so the global identity applies again:

```bash
git config --local --unset user.name
git config --local --unset user.email
```

The fixture's third write, `commit.gpgsign = false`, landed on a key that may have held the same
value before. Check it against what the checkout is meant to have.

## Related Issues/PRs

1. #1894 — the fixture, the first scrub, and the smoke runner's environment strip.
2. `docs/pull-requests/2026-09-04-test-hygiene-codex-env-and-host-isolation.md`, section
   "Hook environment" — the incident as first recorded.

## Not in this PR

1. The launcher strip (`bb-test-runner/sanitize-git-worktree-env`, `scripts/test-changed-bricks.bb`)
   still drops four names. Fourteen other test namespaces run `git config user.*` in temp
   directories with no scrub of their own and depend on it.
2. The project-level twin test runs under `bb test:integration`, which CI runs on pushes to `main`.
   The brick test is the one that runs on a pull request.
3. `delete-tree!` follows symbolic links, and `git init` copies the developer's template directory
   into the host repository. A template that links its hooks directory would be followed on cleanup.
4. The installed hook in the affected checkout is an older script, reached through an absolute
   `core.hooksPath`. The tracked `.githooks/pre-commit` rejects a commit email outside
   `@miniforge.ai` and would have stopped the first commit made with the test identity.
5. Commits already pushed with the test identity are not re-authored.

## Checklist

- [x] Root cause reproduced in a scratch repository
- [x] Both fixture twins changed identically
- [x] Regression tests added for both twins and shown to fail without each defence
- [x] Project-level test registered with `bb test:integration`
- [ ] Shared config of the affected checkout repaired (owner's decision)
