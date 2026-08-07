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

`ai.miniforge.dag-executor.host-git-guard` snapshots the host checkout and
compares two snapshots.

**Redirect config is the invariant.** `:clean?` is true when every key matching
`^(remote\..*url|url\..*insteadof)$` came through the run unchanged.

- `remote\..*url` is anchored on `url$` rather than `\.url$`, so `pushurl` —
  same redirect power, less visibility — is not missed.
- `url\..*insteadof` covers `url.<base>.insteadOf` and `pushInsteadOf`. One
  `git config url."https://evil/".insteadOf https://github.com/` inside a task
  worktree redirects every subsequent fetch and push on the host without
  touching any `remote.*` key at all — the direct bypass of this invariant,
  added after review caught it.
- Values are compared as **vectors**. Git config is multi-valued: a second
  `remote.origin.url` line is legal and `git remote get-url` resolves to the
  *first*, so collapsing to one value missed a rewrite of exactly the value
  git uses.
- **Credentials are stripped as values are read**, so no snapshot and no report
  ever holds one. The likeliest drift is the token-bearing URL the release path
  writes, and a guard that published it while reporting it would be its own
  leak.
- `git config --get-regexp` exits 1 when nothing matches, which is a repo with
  no remotes and not a failure; it reports an empty map. Any other non-zero
  exit is a read failure.

**Remote-tracking refs are reported, not enforced.** They are listed in
`:ref-rewinds` and deliberately do not feed `:clean?`. A moved ref that is not a
fast-forward is indistinguishable from an ordinary `git fetch` after upstream
force-pushed: the default refspec is `+refs/heads/*:refs/remotes/origin/*` and
the `+` forces every opportunistic update. Miniforge force-pushes its own task
branches (`git push --force-with-lease`), and `release-executor/sandbox.clj:370`
fetches a *parent task's branch* in the stacked-DAG shape, so enforcing here
would fail ordinary `bb dogfood` runs. This is a correction from review — the
first draft treated a rewind as drift. Telling a local rewrite from an upstream
one needs the remote's own answer (`git ls-remote`), which this does not ask
for; that is a follow-up.

Refs that appear or disappear are not reported at all — `fetch.prune`
legitimately retires a remote-tracking ref when its upstream branch is deleted.
A ref whose ancestry probe could not be answered is listed with
`:fast-forward? nil`.

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

Unit (no git): `changed-redirect-config` reports edits, additions, removals,
and a rewrite of the *first* value of a multi-valued key; `moved-remote-refs`
skips refs only one snapshot has; `redact-credentials` strips
`https://user:secret@host` and leaves an scp-style `git@github.com:o/r.git`
intact.

Integration (real git):

- A linked worktree runs `git remote set-url origin`, the host's URL changes,
  and `drift` reports it with both values. This is the 2026-08-06 shape.
- A linked worktree sets `url.<base>.insteadOf`; drift is reported even though
  no `remote.*` key moved.
- A **real** `git fetch` against a real upstream that fast-forwarded is clean.
- A **real** `git fetch` after the upstream force-pushed is also clean, with the
  forced update present in `:ref-rewinds` as context. This is the case that
  decides whether stacked `bb dogfood` runs still pass, and it is driven through
  actual `git push --force` / `git fetch` rather than a simulated `update-ref`,
  because the forced-update semantics only exist in the real refspec. The first
  version of the fixture amended an empty commit without `--allow-empty`, which
  fails quietly and left the branch unmoved — the test passed for no reason
  until that was found.
- A ref moved off its own history is reported but does not set `:clean?` false.
- A ref retired by `fetch.prune` is not reported.
- A snapshot of a path that is not a repository is an error, not an empty
  snapshot that would read as "nothing to compare". (`git config --get-regexp`
  exits 1 in a non-repo, the same code as "no remotes", so the ref half is what
  makes this fail closed; the docstring says so.)

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
