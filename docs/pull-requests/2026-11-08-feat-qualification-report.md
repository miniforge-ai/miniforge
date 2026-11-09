<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Run a gate's scenario and write its qualification report

## Overview

`qualification/run-gate!` runs a gate's scenario, joins its results with the
gate's criteria, records the build, hashes the fixtures and config, and writes
the report as EDN and JSON. A scenario that throws or returns malformed output
still produces a report, with every criterion unrun. Stacked on the schema PR.

## Motivation

Gate evidence must be repeatable and tied to an immutable build, and an unrun
criterion must never read as a pass. The schema PR defines the report; this
one produces it, and is the boundary where scenario code's failures become
data.

## Changes in Detail

1. `build`: the commit checked out (nil when git cannot read it), whether git
   reports a clean tree (false when it cannot tell), and a content hash of
   `deps.edn` and `workspace.edn`. An unreadable build is never clean. git runs
   with the process environment; a launcher started from a git hook must strip
   repository-binding variables such as `GIT_DIR`.
2. `writer`: `<out-dir>/<gate>/<started-at>-<commit>.edn` and `.json`. JSON
   keeps namespaced keys and keyword values.
3. `verdict/output-problems`: output that is not a `ScenarioOutput`, holds
   values that are not plain EDN data (fixtures, config or results), or names
   undeclared or duplicate criteria.
4. `run`:
   1. `run-gate!` catches a scenario's `ex-info`, any `Exception`, and a failed
      `assert`; the report then records the failure and every criterion is
      unrun, so the verdict is incomplete.
   2. A report that cannot be written comes back as `:write-failure`; an
      invalid gate as `:invalid-gate`.
   3. `exit-code`: 0 only for a written report with a passing verdict, 2 for
      an invalid gate, 1 otherwise. `summary-lines` for console output.
5. `interface`: `run-gate!`, `exit-code`, `summary-lines`.

## Testing Plan

1. `build_writer_test`: outside a repository, no commit and not clean; the
   workspace commit is a full SHA; the deps hash follows `deps.edn`; report
   paths name gate and run; EDN and JSON round-trip.
2. `interface_test`: a passing run is valid, written and exits 0; a throwing
   scenario (`ex-info`, `assert`) and unusable output (malformed, a fn in the
   fixtures or in observed state, an undeclared criterion) are reported with
   every criterion unrun; an unreported criterion is incomplete; hashes repeat
   across runs; an invalid gate exits 2; an unwritable report exits 1 even
   when every criterion passed; the summary names the verdict and each row.
3. Locally: 23 tests, 79 assertions, 0 failures.

## Deployment Plan

None. `bb qualify <gate-id>` and the first gate scenario (#2422) follow.

## Related Issues/PRs

Stacked on the schema PR. Shared harness for tracker #2421.

## Checklist

- [x] A scenario failure is a report, never a pass.
- [x] Fixture and config hashes repeat across runs.
- [x] Report written as EDN and JSON.
