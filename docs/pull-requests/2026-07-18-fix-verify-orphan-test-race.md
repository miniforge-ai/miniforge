<!--
  Title: Fix reap-race flake in the verify orphan-process test
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(phase-software-factory): bounded wait in the orphan-process verify test

Branch: `fix/verify-orphan-test-race`

## Summary

`run-tests-kills-child-process-not-just-shell-test` kills a
`sh -c "sleep 7200"` tree, slept a fixed 250ms, then asserted the JVM's
descendant table held no `sleep`. On a loaded CI runner the kill lands
but the process table can take longer than 250ms to clear, so the reap
race read as a leak — it failed main's CI at `62db3519b` and both runs
on PR #1420 (2026-07-18/19) while passing locally every time.

Two changes:

- The fixed beat becomes a bounded poll (100ms interval, 5s deadline).
  The deadline only spends fully when the tree genuinely leaks — the
  2-hour sleep this test exists to catch — so the regression signal is
  intact and the timing assumption is gone.
- The descendant filter reads the command with `.orElse ""` instead of
  `Optional.get` — a just-killed zombie can report an empty command
  Optional, which previously would have thrown instead of reading as
  "not an orphan".

## Test plan

- `clj-kondo` clean; `poly test brick:phase-software-factory` green
  locally.
- The real proof is CI on this PR plus subsequent main runs going
  green where the flake was intermittent.
