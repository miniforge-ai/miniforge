<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(n13): Resume Signal Dispatcher (N13 §2.7)

## Overview

When a PR with registered listeners merges, build a structured resume
primer per N13 §2.7 and deliver it to each `:active` listener via its
declared channel, then transition the listener to `:dispatched`. CLI:
`bb miniforge pr resume-dispatch --repo <path>`.

This is the operator-load killer the listener registry (#841/#843)
unblocked. Per the #808 mining, manual `merged` acks were the highest-
volume operator turn (~448 instances across recent sessions); this
dispatcher retires that whole class for any agent registered with a
webhook listener.

## Mechanism

```text
read .miniforge/listener-registry.edn
        │
        ▼
group active listeners by PR URL
        │
        ▼
for each PR:  gh pr view <n> --json state,mergeCommit,mergedAt
        │
        ▼
        state == MERGED?
        │       │
       no      yes
        │       │
       skip    for each :active listener:
                 build N13 §2.7 resume primer
                 dispatch via channel handler
                 mark :dispatched (with dispatch-id)
```

## Channel handlers (v0)

| Channel | v0 status |
|---|---|
| `:webhook` | **Real**: HTTP POST primer JSON, bounded retries (default 3) with exponential backoff (default 500ms base), 10s per-attempt timeout. Tunables in `resources/config/resume-dispatcher/defaults.edn`. |
| `:pty` | Stub — returns `:resume-dispatcher/not-yet-wired`. Needs Drive console PTY host (separate component). |
| `:miniforge-ipc` | Stub — returns `:resume-dispatcher/not-yet-wired`. Needs an event-stream subscriber. |

Listeners with `:pty` or `:miniforge-ipc` channels register cleanly
(per #841 the registry already accepts them), but `dispatch-listener!`
returns the typed `:not-yet-wired` error so the operator sees the gap
and can re-register with a webhook in the meantime. The two stubs
become real handlers when their cooperating runtimes ship.

## Public API (re-exported through `pr-lifecycle.interface`)

| API | Behavior |
|---|---|
| `build-resume-primer` | Pure: assemble the N13 §2.7 primer from a merged-PR record + listener |
| `channel-supported?` | Pure predicate: does this listener's channel kind have a real handler? |
| `dispatch-resume-listener!` | Build + dispatch + mark for one listener; failure decorated with `:listener/id` for diagnostics |
| `dispatch-pr-merge!` | Walk every `:active` listener for a PR; per-listener failures collected, not aborting |
| `resume-dispatcher-supported-channel-kinds` | The v0 set (`#{:webhook}`) |

## Webhook payload shape (per N13 §2.7)

```json
{
  "resume/pr-url":       "https://github.com/o/r/pull/42",
  "resume/merge-sha":    "abc1234deadbeef",
  "resume/merged-at":    "2026-05-10T20:54:45Z",
  "resume/diff-summary": null,
  "resume/listener":     {"agent/id": "agent-A", "session/id": "sess-001"}
}
```

Cheshire's default keyword serialization preserves the
`<ns>/<name>` form, so receivers parse `"resume/pr-url"` directly —
no key transformation needed.

## Operator surface

```bash
# Single-pass over all active listeners (default cwd):
bb miniforge pr resume-dispatch

# Against an explicit checkout:
bb miniforge pr resume-dispatch --repo /path/to/repo
```

External cron / loop wraps `--once` for v0. v1 will fold a poll-
interval daemon into the command.

## Failure semantics

- **Per-PR gh failure** (e.g. PR doesn't exist anymore, gh auth issue):
  surfaces an error line, skips the PR, continues to the next.
- **Per-listener delivery failure**: collected into the pass result's
  `:failed` vector with `:listener/id` decoration. Listener stays
  `:active` so the next pass retries.
- **Webhook 5xx / network errors**: bounded retries with exponential
  backoff. After exhaustion, listener stays `:active`.
- **Marked-failed-after-delivery** (delivery succeeded but registry
  write failed): typed `:resume-dispatcher/marked-failed-after-delivery`
  surfaces the degraded state. Next pass re-delivers (cheap because
  webhook receivers are expected to be idempotent on
  `:resume/pr-url` together with `:resume/merge-sha`).

## Test plan

- [x] `clj-kondo`: clean.
- [x] `resume-dispatcher-test`: 15 tests / 50 assertions pass.
- [x] `listener-registry-test`: 25 tests / 76 assertions still pass.
- [x] Full namespace tree loads under `:dev:test`.
- [x] `bb pre-commit`: ✅ ALL PRE-COMMIT CHECKS PASSED.
- [ ] Live smoke test against a real merged PR with a registered
      webhook listener (manual, post-merge).

## What's NOT in this PR (deferred)

- **Daemon loop** — v1 will fold `--poll-interval` into the command.
- **Webhook server / GitHub App** — production-grade trigger
  (subscribe to `pull_request.closed.merged`); the v0 polling path
  gets the same operator outcome immediately.
- **`:pty` and `:miniforge-ipc` channel handlers** — wired when
  cooperating runtimes ship.
- **Diff-summary in primer** — currently nil; `gh pr diff --stat`
  is a small follow-up.
- **Resume-channel auth** — primer transport is HTTP today; signed
  payloads / mTLS are v1.

## References

- Spec: N13 §2.7 + `specs/informative/n13-listener-registry.md`.
- #808 — N13 foundations.
- #818 — `pr review --post`.
- #837 — `pr review-monitor`.
- #841 — listener registry implementation (the prerequisite this PR
  unblocks).
- #843 — listener registry strata fix.
