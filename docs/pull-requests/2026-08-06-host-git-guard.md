<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: observe the git state a task worktree shares with its host checkout

**Theme:** executor isolation
**Stack:** second of three. Base is the stratum-lint chore; the third PR
wires this into the executor registry.
**Follows:** PR #1685 (bench sandbox isolation), whose follow-ups named this
exposure.

## Problem

A linked `git worktree` owns HEAD, the index, and the working tree.
`.git/config` and every ref outside HEAD live in the shared common dir.
Verified against real git rather than taken from documentation:

```text
$ git -C host worktree add --detach wt
$ git -C wt remote set-url origin https://example.invalid/EVIL.git
$ git -C host config --get remote.origin.url
https://example.invalid/EVIL.git

$ git -C wt update-ref refs/remotes/origin/main HEAD
$ git -C wt branch leaked-branch
$ git -C host show-ref
... refs/remotes/origin/main   (moved)
... refs/heads/leaked-branch   (created)
```

On 2026-08-06 this reached the live miniforge checkout through the bench
sandbox: `remote.origin.url` was repointed at a local bare mirror and
`refs/remotes/origin/main` was force-updated backwards. Branches cut from
`origin/main` afterwards were based on a stale commit and their pushes went to
the mirror, so the PRs never appeared on GitHub.

The worktree executor gives every task run the same shape. This PR adds the
observation; the next one enforces on it.

## Changes in Detail

`ai.miniforge.dag-executor.host-git-guard` snapshots the two things a task run
has no legitimate reason to change, and compares two snapshots.

- **Remote URLs** must be byte-identical across a run. The config regexp is
  `^remote\..*url$` rather than `^remote\..*\.url$`, so `pushurl` — same
  redirect power, less visibility — is not missed. `git config --get-regexp`
  exits 1 when nothing matches, which is a repo with no remotes and not a
  failure; it reports an empty map. Any other non-zero exit is a read failure.
- **Remote-tracking refs** may fast-forward but must stay on their own
  history, tested with `git merge-base --is-ancestor`.

Two decisions worth naming, because they are what make the guard usable rather
than a nuisance:

- **Refs that appear or disappear are not drift.** `fetch.prune` legitimately
  retires a remote-tracking ref when its upstream branch is deleted, and
  flagging that would fire on ordinary runs.
- **An unanswerable ancestry probe counts as a rewind.** If an object has been
  pruned out of the store, `merge-base` can answer neither yes nor no; the
  guard fails closed, because the alternative is waving through exactly the
  mutation it exists to catch.

`drift` reports and does not judge. Whether a dirty report should fail a run
is the caller's policy, and lives in the next PR.

Message strings go through a system-locale catalog
(`config/dag-executor/host-git-guard/messages/system.edn`) — every one of them
lands in a log line or an anomaly payload, never a user surface.

## Layer

`dag-executor` component. Layers 0 (pure comparison), 1 (git plumbing reads),
2 (snapshot and drift report). No brick dependencies added.

## Testing

Real git against throwaway repos. The leak is a property of how git shares a
common dir between a checkout and its linked worktrees, so a mocked git cannot
observe it.

Unit (no git): `changed-remote-urls` reports edits, additions and removals;
`moved-remote-refs` skips refs only one snapshot has.

Integration (real git):

- A linked worktree runs `git remote set-url origin`, the host's URL changes,
  and `drift` reports it with both values. This is the 2026-08-06 shape.
- A fetch that fast-forwards `origin/main` is clean — the case that decides
  whether the guard is usable in a normal dogfood run.
- A force-update backwards is drift, carrying the ref and both SHAs.
- A ref retired by `fetch.prune` is not drift.
- A snapshot of a path that is not a repository is an error, not an empty
  snapshot that would read as "nothing to compare".

Fixture remote URLs are `.invalid`, a reserved TLD that can never resolve, so
a test that accidentally reached the network would fail rather than touch a
real remote.

`dag-executor` component suite green: 423 tests, 1745 assertions.
`bb poly:check` clean (only pre-existing warnings 202/205/207).

## What this does NOT do

Nothing calls it yet — that is the next PR in the stack, deliberately split so
the observation and the enforcement policy can be reviewed apart.

It detects; it does not prevent. A task run can still rewrite the host's config
and refs, and the damage still has to be repaired by hand. Only a sandbox that
does not share a common dir prevents; see the third PR's follow-ups for what
that would take.
