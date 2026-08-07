<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: isolate the bench sandbox from the checkout it was launched from

## Overview

Provision the bench/dogfood sandbox as an independent clone, and add a
fail-closed guard that refuses a bench sharing a git common dir with the
launching checkout.

## Motivation

On 2026-08-06 a bench run left the live miniforge checkout with
`remote.origin.url` pointing at `~/.miniforge/bench/origin.git` and
`refs/remotes/origin/main` force-updated *backwards* to that mirror's
stale head. Branches cut from `origin/main` afterwards were based on a
stale commit, and their pushes went to the local mirror, so the PRs never
appeared on GitHub.

The bench sandbox at `~/.miniforge/bench/repo` was a linked `git worktree`
of the live checkout:

```text
$ git -C ~/.miniforge/bench/repo rev-parse --git-dir --git-common-dir
/Users/chris/ws/miniforge.ai/miniforge/.git/worktrees/repo
/Users/chris/ws/miniforge.ai/miniforge/.git
```

A linked worktree owns HEAD, the index, and the working tree. `.git/config`
and every other ref live in the shared common dir. So the bench's
deliberate `git remote set-url origin <mirror>` — intended to stop a
completed run opening a real PR — rewrote the launching checkout's origin,
and its `git fetch origin main` rewrote the launching checkout's
remote-tracking ref. Neither is recoverable by re-running the bench; both
are silent.

This is the same class as the known workflow-runtime sandbox leak: an
isolation mechanism that isolates less than the caller assumes.

## Layer

Development tooling (`tasks/`), not a runtime brick.

## Changes in Detail

- `bench-git` — total git plumbing. A failing git call, or a `dir` that
  does not exist, comes back as a value; `bench/verify` is routinely
  handed a path an operator typed.
- `bench/isolation-report` — compares the bench's per-worktree git dir
  against its common dir, and its common dir against the launching
  checkout's. Either overlap means `set-url`/`fetch` inside the bench
  mutates the other checkout.
- `bb bench:verify` — fail-closed CLI gate over that report, for a runner
  to call before it touches anything.
- `bb bench:provision` — clones the launching checkout, inits the bare
  mirror, and redirects only the clone's origin. Captures the launching
  checkout's `remote.origin.url` before the work and re-checks it after;
  a mismatch fails the provision. Refuses an existing `<root>/repo`,
  since a bench in flight keeps its worktrees and task branches there.
- `qualified-branch-ref` — normalizes a push destination to exactly one
  `refs/heads/` prefix. Git DWIMs an unqualified destination, so one
  already carrying a partial `heads/...` prefix expands to
  `refs/heads/heads/...`.

## On the malformed `refs/heads/heads/main`

Reported as a suspected refspec bug in the run's push path. It is not.
The ref lives in the **live checkout** and predates the bench:

```text
$ git reflog show refs/heads/heads/main --date=iso | tail -1
93c56dafa refs/heads/heads/main@{2026-05-19 19:31:14 -0700}: branch: Created from origin/main
```

Nine reflog entries follow over two days, all `pull --ff-only` and
`reset: moving to origin/main` — a branch literally named `heads/main`
that someone checked out and worked on. No committed code in this repo
constructs a branch name by stripping only `refs/`, and no source file
runs `git pull --ff-only` at all. The bench mirror acquired the junk ref
because it was cloned from the bench worktree, which shares `refs/heads`
with the live checkout.

Two things follow. The generic fix is in this PR: refspecs are fully
qualified via `qualified-branch-ref`, and the mirror is seeded by explicit
push rather than by mirroring every ref, so junk refs in a source can no
longer reach a bench mirror. The specific cleanup is a one-line ref
deletion in the live checkout, left to the operator because a bench run
was in flight:

```bash
git -C /path/to/checkout branch -D heads/main
```

## Testing

`development/test/bench_test.clj` runs real git against throwaway repos.
The leak is a property of how git shares a common dir between linked
worktrees, so a mocked git cannot observe it — the last test creates a
linked worktree, runs `remote set-url` inside it, and shows the launching
checkout's origin change.

- Provisioning leaves the launching checkout's `remote.origin.url`
  byte-identical (the regression the incident was missing).
- The seeded mirror carries exactly `refs/heads/main`.
- `qualified-branch-ref` collapses `heads/main` and
  `refs/heads/heads/main` to `refs/heads/main`, and leaves
  `feature/heads-up` intact.
- Provisioning refuses to clobber an existing bench.

Verified against the real leaky bench, which `bb bench:verify` rejects
with exit 1, and against a freshly provisioned one, which it accepts.

## Follow-ups

- The `eval/codex-traps/` bench harness is untracked on disk and was not
  modified — a run was in flight against it. It needs a `bb bench:verify`
  gate at startup and should be committed.
- The worktree executor (`dag-executor`) gives task runs the same
  filesystem-only isolation. Nothing in a task run sets remotes today, but
  an agent that ran `git remote set-url` or `git fetch origin` inside its
  task worktree would reach the host checkout the same way.
