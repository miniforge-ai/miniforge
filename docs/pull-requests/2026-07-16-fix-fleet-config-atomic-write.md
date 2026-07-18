<!--
  Title: Fleet config read-modify-write race condition
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(fleet-config): use advisory file lock for atomic config read-modify-write

Branch: `fix/fleet-config-atomic-write`

## Summary

`~/.miniforge/config.edn` is shared state written by two independent
components — `web-dashboard` and `pr-sync` — both of which performed
bare `slurp`/`spit` read-modify-write cycles with no coordination.  A
concurrent `add-repo!` from the TUI and a `discover-configured-repos!`
from the dashboard could each read the same stale config, compute
divergent next states, and silently clobber each other's write.  The
last writer won; repos added by the first writer were lost.

## Fix

Both components now wrap every read→modify→write sequence in a
`with-config-lock!` helper that holds an exclusive advisory
`java.nio.channels.FileChannel` lock across the full window.  The
pattern is taken directly from
`ai.miniforge.dag-executor.scratch-gc-queue/with-queue-lock!`, which
already uses this approach for the GC queue.

Key behaviour:
- Polls for up to 500 ms (25 ms intervals) before giving up with a
  `result-failure` — same contract as the GC queue's `:locked` no-op.
- `StandardOpenOption/CREATE` ensures the FileChannel can open before
  the config file exists (first-run case).
- `OverlappingFileLockException` / `ClosedChannelException` are caught
  by class name (not a class literal) so the namespace remains loadable
  under babashka, which does not expose `java.nio.channels.FileLock`.
- Only the read→modify→write thunk is locked; pure reads
  (`load-fleet-config`, `get-configured-repos`) are left unlocked —
  they are idempotent and called at high frequency from the dashboard.

## Changes

| File | Change |
|------|--------|
| `components/pr-sync/src/ai/miniforge/pr_sync/core.clj` | Add `:import` for NIO; add `config-lock-timeout-ms` / `config-lock-poll-ms` constants; add `with-config-lock!`; wrap `add-repo!`, `remove-repo!`, and `discover-repos!` |
| `components/web-dashboard/src/ai/miniforge/web_dashboard/state/trains.clj` | Same: add `:import`, constants, `with-config-lock!`; wrap `add-configured-repo!` and `discover-configured-repos!` |

## Test plan

- `bb poly:check` clean (no new deps introduced; NIO classes are JDK built-ins).
- Manual concurrent test: run `add-repo!` and `discover-repos!` from two
  processes simultaneously; verify neither writer loses entries.
- Existing `pr-sync` and `web-dashboard` test suites pass unchanged —
  locking is transparent to callers that don't exercise concurrency.
