<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(release-executor): pass the release branch as --head to gh pr create

## Overview

Release now tells `gh pr create` which branch the PR comes from, as
`--head <owner>:<branch>`. Before, gh worked it out and could fail to find a
branch that release had just pushed.

## Motivation

A dogfood run of `work/workflow-resume-emits-resumed-event.spec.edn` on
2026-11-08 passed verify and review, pushed its branch to origin, and then
failed at release:

```text
aborted: you must first push the current branch to a remote, or use the --head flag
```

The branch was on origin, its upstream was set, and `origin/<branch>`
matched HEAD. The same error reproduces outside the run with
`gh pr create --dry-run` (gh 2.102.0) in a worktree on that branch.

The cause is gh's head detection. The checkout has a second remote,
`github`, with the same URL as origin. gh looks for a remote-tracking ref
equal to HEAD and checks remotes in a fixed order, `github` before `origin`.
It runs one `git show-ref --verify` over all the candidates, and that
command exits at the first ref that does not exist:

```text
git show-ref --verify -- HEAD refs/remotes/github/<branch> refs/remotes/origin/<branch>
fatal: 'refs/remotes/github/<branch>' - not a valid ref
```

gh never sees the origin ref, decides nothing was pushed, and aborts because
the run cannot answer a prompt. With `--head`, gh skips this lookup;
`gh pr create --dry-run --head <branch>` succeeds in the same worktree.

## Changes in Detail

1. `pr_lookup.clj`:
   1. `branch-argv` and `push-url-argv`, the git commands both backends run.
   2. `head-ref`: `<owner>:<branch>` when origin's push URL is an SSH or
      HTTPS URL of an `<owner>/<repo>` path, else the bare branch.
2. `git.clj` and `sandbox.clj`, `create-pr!`:
   1. Read the checked-out branch (`git branch --show-current`) and origin's
      push URL, then pass `--head` from `head-ref`.
   2. A detached HEAD prints no branch; gh then runs without `--head`, as
      before.
   3. A branch name the existing guard rejects returns that failure without
      running gh. The sandbox puts the head into a shell command, so the
      guard matters there.
   4. An interrupted or timed-out lookup returns that result without running
      gh (`pr-lookup/head-from-lookups`). A cancelled release does not open a
      PR after the cancellation.
   5. The sandbox lookups take `exec-opts` without its `:env`: they run in
      the directory gh will run in, without the token.
   6. The base-branch check still runs first, before any process starts.
   7. Host: the gh argv is built by a new pure `pr-create-argv`. Sandbox: the
      stratum autofix moved `create-pr!` to Layer 1, because it now calls a
      same-file Layer 0 helper.
3. `git/exec!`: an interrupt now comes back with `:interrupted? true` and
   the thread's interrupt flag set again, as `raw-push!` and `create-pr!`
   already do. Before, `exec!` cleared the flag and returned a plain
   failure. Release steps stop after a failed step, so other callers see
   the change only when the run is being cancelled.
4. Tests:
   1. `pr_lookup_test`: `head-ref` for scp-style SSH, `ssh://`, HTTPS, and
      URLs with no owner to read. `head-from-lookups` for a branch, a
      detached HEAD, and an interrupted or timed-out lookup.
   2. `git_test` and `sandbox_test`: on a branch, with and without an
      origin; detached HEAD; an unsafe branch name. Git replies are the
      outputs git 2.48 prints in each case.
   3. Cancellation: on the host, `process/shell` interrupted during the
      lookup; in the sandbox, an interrupted or a timed-out lookup. Also the
      sandbox lookups' directory and env.
   4. The GH_TOKEN and redaction tests stub local git to print no branch, so
      they still see the gh call they check.
   5. `fake_remote` answers `git branch --show-current` with its checked-out
      branch, so the release pipeline and crash-matrix tests run the new
      path.

Why the owner: with a bare `--head <branch>`, gh means a branch in the base
repository. Take a fork checkout: origin is the fork and an `upstream`
remote is the parent. When gh resolves the base to the parent, its own
detection named the head `fork-owner:<branch>`. The owner prefix keeps that
case working. It was not tested against a real fork.

Why the backends read the branch themselves: `step-create-pr` could pass
it, but `core.clj` has six layers. Stratum-lint SL003 fails any edit to it
until it is split. The worktree's checked-out branch is the branch release
created and pushed, so both give the same value.

Not changed: `reuse-existing-pr!` still runs `gh pr view` without arguments,
which uses gh's own branch lookup. It is not on the failing path and was not
checked against this remote layout.

## Testing Plan

1. `ai.miniforge.release-executor.*` tests: 315 tests, 935 assertions, 0
   failures.
2. With `git.clj` and `sandbox.clj` reverted to main, the head tests fail 7
   assertions. Reverted to the PR's first push, the interrupt, timeout and
   workdir tests fail 8.
3. `gh pr create --dry-run` in a worktree on the run's branch: aborts without
   `--head`; with `--head miniforge-ai:<branch>` it reports the PR it would
   create.
4. Not yet verified end to end: a real `gh pr create` with an
   `<owner>:<branch>` head. The next step after merge is to finish the
   2026-11-08 run's release, which does exactly that.
5. CI runs the full suite.

## Deployment Plan

None. Takes effect on the next release.

## Related Issues/PRs

1. Found while dogfooding `work/workflow-resume-emits-resumed-event.spec.edn`,
   after #2459 let that run's review fit the context window.
2. The newest PR from an `mf/` release branch is from 2026-06-17
   (`gh pr list --search head:mf/`). The `github` remote in this checkout
   was first fetched in August.

## Checklist

- [x] Regression tests fail on main and pass here
- [x] Reproduced with gh outside the run
- [x] Rules review against the pinned standards
- [ ] CI green
- [ ] Copilot review addressed
- [ ] The 2026-11-08 run's release opens its PR
