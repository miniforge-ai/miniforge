<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(n13): `pr review-monitor` — auto-trigger Standards Reviewer over a fleet

## Overview

Adds a single-pass auto-trigger over the `pr review --post` flow shipped
in #818. `bb miniforge pr review-monitor --repo <path> --author <login>`
walks every open PR authored by `<login>`, dedups against PRs we've
already standards-reviewed at their current head SHA, and posts a fresh
review on the rest — each in an ephemeral git worktree pinned to the
PR's head, so the operator's working checkout is untouched.

This is the second half of the deferred N13 implementation work
called out at the end of #818. v0 ships `--once` semantics only; the
persistent daemon loop is intentionally a v1 follow-up.

## Why

The mining behind N13 (#808) ranked manual `merged` confirmations
(~448 turns) and `respond-to-comments` directives (~80 turns) as
the top operator workload. Those numbers only retire when the
review loop fires without the operator. #818 made `--post` work;
this PR makes it *fire*.

## Mechanism

```text
                  open PRs in fleet
                        │
        gh pr list --author <login> --json …
                        │
                        ▼
   for each PR:  gh api .../reviews --paginate --jq filter-by-marker
                        │
                        ▼
        partition (needs-review / already-reviewed) by head SHA
                        │
                        ▼
   for each needs-review PR:
        git fetch +refs/pull/<n>/head:refs/miniforge-review/pr-<n>
        git worktree add --detach /tmp/mf-review-<sha> <sha>
        run scan/classify/render/post (existing #818 path)
        git worktree remove /tmp/mf-review-<sha>
```

**Dedup mechanism**: every review body posted via `post-review!` now
embeds an invisible HTML comment marker
`<!-- miniforge:policy-eval -->`. The scheduler queries
`gh api repos/{owner}/{repo}/pulls/<n>/reviews --paginate`, filters
reviews whose body contains the marker, and skips PRs whose head
SHA is in the resulting set. The marker is invisible in PR Markdown,
greppable in the API response, and is the only reliable per-review
identity signal — `gh api` doesn't surface a stable bot identity for
reviews posted under a personal access token.

**Ephemeral worktrees**: each PR gets its own detached worktree at
`/tmp/mf-review-<sha>` so we never disturb the operator's working
checkout. `with-pr-worktree` brackets fetch + add + cleanup; failure
to add still triggers cleanup of any partial leftover.

## What's new

### `components/pr-lifecycle`

- `github.clj` — `review-marker` constant + `ensure-marker` helper.
  `post-review!` always tags posted bodies (idempotent — caller-supplied
  bodies that already carry the marker aren't double-tagged).
- `review_scheduler.clj` (new):
  - `existing-review-shas worktree-path pr-number` — paginated
    `gh api .../reviews` query, marker-filter, returns
    `(dag/ok #{sha …})`.
  - `pr-needs-review?` / `partition-needs-review` — pure predicate +
    splitter against `pr-poller`-shaped maps.
  - `fetch-pr-head!` / `add-pr-worktree!` / `remove-pr-worktree!` /
    `with-pr-worktree` — ephemeral-worktree bracketing with
    `git fetch +refs/pull/<n>/head:refs/miniforge-review/pr-<n>`.
- `interface.clj` — re-exports `review-marker`, `existing-review-shas`,
  `pr-needs-review?`, `partition-needs-review`, `with-pr-worktree`,
  `fetch-pr-head!`.
- `test/.../review_scheduler_test.clj` (new) — 8 tests / 27
  assertions covering marker-filter, paginate concat, blank/empty
  edges, gh-failure bubbling, predicate semantics (incl. `nil` and
  wrong-type SHAs), and partition.
- `test/.../github_test.clj` — extended: posted body always contains
  the marker; pre-marked bodies aren't double-tagged.

### `bases/cli`

- `commands/pr_review_monitor.clj` (new):
  - `pr-review-monitor-cmd` — single-pass orchestrator.
  - `run-pass!`, `existing-shas-by-pr`, `review-one-pr!`, `gh-pr-base-ref`
    — small private helpers; per-PR errors surface to operator and
    don't abort the pass.
- `main.clj` — registers `pr review-monitor` with the flag spec
  `{:repo, :author, :standards, :pack, :rules, :once}`.
- `messages/en-US.edn` — nine new `:pr/review-monitor-*` keys.

## Operator surface

```bash
# Single-pass over all open PRs you authored:
bb miniforge pr review-monitor --author chrislester --repo .

# Same, against an explicit checkout + a non-default standards pack:
bb miniforge pr review-monitor \
    --repo /path/to/repo \
    --author miniforge-bot \
    --pack miniforge-standards
```

External cron / loop wraps the `--once` invocation for v0. v1 will
fold the loop into the command (`--poll-interval`).

## Reuse anchors (no new runtime surface)

| New piece                | Reuses                                                          |
| ------------------------ | --------------------------------------------------------------- |
| `existing-review-shas`   | `gh api ... --paginate` + cheshire — same plumbing as `pr-poller` |
| `with-pr-worktree`       | `git worktree add --detach` + `git fetch refs/pull/<n>/head`     |
| Per-PR review run        | `pr-review/run-pr-review!` from #818 — unchanged                 |
| Marker-on-body           | `post-review!` from #818 — single one-line addition              |
| Open-PR enumeration      | `pr-poller/poll-open-prs` — unchanged                            |

## Test plan

- [x] `clj-kondo` on touched files: clean.
- [x] `github-test` + `review-scheduler-test`: 14 tests / 56
      assertions pass under `:dev:test`.
- [x] Full namespace tree loads.
- [ ] `bb pre-commit`: pending (will run on commit).
- [ ] Live smoke test against an open miniforge PR set (manual,
      post-merge).

## What's NOT in this PR (deferred)

- **Daemon loop** — v1 will fold `--poll-interval` into the command.
- **Webhook subscriber** — the eventual production path
  (`pull_request.opened` / `synchronize`); requires hosting + signature
  verification + GitHub App. v0 polling-based approach gets the same
  operator outcome immediately.
- **Per-rule pack scheduling** — currently one pack per pass.
- **Listener registry implementation** (N13 §2.7) — schema landed in
  #808, impl is its own PR.
- **Resume Signal Dispatcher** — its own PR.

## References

- N13 §2.2 — Standards Reviewer auto-trigger requirement.
- #808 — N13 foundations (renderer + listener-registry spec + CLI seam).
- #812 — test-runner doc deepening.
- #818 — `pr review --post` (the path this PR auto-fires).
