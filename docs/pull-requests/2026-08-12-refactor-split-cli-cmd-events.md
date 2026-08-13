<!--
  Title: Split cli/main/commands/events.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split commands/events.clj (rule 210)

## Overview

Splits the `events show` core read/render logic out of
`ai.miniforge.cli.main.commands.events` into its own sibling namespace,
`ai.miniforge.cli.main.commands.events.show`, resolving a stratum-lint
SL003 finding (the combined namespace measured 4 real layers, over the
rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli`
batch (13 concurrent `main/commands/*.clj` splits). `events.clj` (115
lines) mixed two concerns at different layers: pure/IO-isolated event
reading + timeline rendering (3 layers) and CLI opts/exit-code wiring
(1 layer on top) — together 4 layers, over budget.

## Changes in Detail

- New file `commands/events/show.clj`
  (`ai.miniforge.cli.main.commands.events.show`): the core logic
  (`default-gap-threshold-secs`, `gap-threshold-ms`, `events-show`) —
  3 layers, unchanged behavior.
- `commands/events.clj`: now only the CLI entry point
  (`events-show-cmd`, renumbered stratum 3 → stratum 0 since it is the
  sole layer remaining) — delegates to `show/events-show`.
- `commands/events_test.clj`: updated requires/call sites so direct
  `events-show` tests call `show/events-show` (new namespace) while
  `events-show-cmd` tests keep calling `sut/events-show-cmd` (name
  unchanged, since `main.clj` still requires
  `ai.miniforge.cli.main.commands.events` for the command entry
  point — no caller-facing change there).

This is pure code motion: no logic changed, only relocated and
re-namespaced.

## Testing Plan

- `stratum-lint` clean on both resulting files (exit 0, was SL003
  exit 1 on the original).
- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.main.commands.events-test) (clojure.test/run-tests 'ai.miniforge.cli.main.commands.events-test)"`
  — 14 tests, 29 assertions, 0 failures, 0 errors.
- `clojure -M:dev:test -e "(require 'ai.miniforge.cli.main)"` — confirms
  `main.clj` (the only non-test caller) still resolves the updated
  dependency chain cleanly.
- `bb lint:clj` clean on the 3 changed files.
- Fan-in check: `ai.miniforge.cli.main.commands.events` is referenced
  only by `main.clj` (`events-show-cmd`, unchanged call site) and
  `events_test.clj` (updated); no `projects/` references found.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program, `bases/cli`
  `main/commands` batch (13 concurrent per-file splits); follows the
  convention established by
  `components/policy-pack/builtin_detectors.clj` (#1730).

## Checklist

- [x] stratum-lint clean on both resulting files
- [x] Direct namespace test run green (14/14, 0 failures/errors)
- [x] `bb lint:clj` clean
- [x] Adversarial self-review: def set unchanged, only relocated
- [x] Fan-in confirmed repo-wide (main.clj + test file only) before starting
- [x] Commit budget: 193/200 reportable lines
