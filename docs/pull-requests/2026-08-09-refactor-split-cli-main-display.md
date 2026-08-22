<!--
  Title: Split cli/main/display.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split main/display.clj (rule 210)

## Overview

Splits the classified-error display group out of
`ai.miniforge.cli.main.display` into a new sibling namespace,
`ai.miniforge.cli.main.display.classified-error`, resolving a
stratum-lint SL003 finding (the combined namespace measured 5 real
layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli`
batch. `bases/cli/src/ai/miniforge/cli/main/display.clj` (196 lines)
was flagged over budget.

## Changes in Detail

- New file `main/display/classified_error.clj`
  (`ai.miniforge.cli.main.display.classified-error`): the per-type
  header printers (`print-agent-backend-error-header`,
  `print-task-code-error-header`, `print-external-error-header`,
  `print-generic-error-header`), the per-type context printers
  (`print-agent-backend-error-context`, `print-task-code-error-context`,
  `print-external-error-context`), `get-retry-recommendation`,
  `print-error-report-url`, the dispatch functions
  (`print-error-header-by-type`, `print-error-context`), the styled
  `print-retry-recommendation`, and the composite
  `print-classified-error` — 3 layers. Requires
  `ai.miniforge.cli.main.display` for the shared `style` primitive.
- `main/display.clj`: keeps the ANSI-styling primitives
  (`ansi-colors`, `style`), the data-driven renderers (`render-fields`,
  `render-section`, `render-detail`), and the generic styled print
  helpers (`print-error`, `print-success`, `print-info`) — now 3
  layers (down from 5).
- Layer numbers (`^{:stratum N}` metadata) recomputed per file: strata
  are a same-namespace call-depth measure, so a function whose only
  local-file callee moved out drops to stratum 0 in its new file even
  though `display.clj` still called it at a higher stratum. No
  function's behavior changed, only its `:stratum` tag and which file
  it lives in.
- `main/commands/run.clj`, the one real caller of
  `print-classified-error` repo-wide (confirmed by grepping the fully
  qualified namespace `ai\.miniforge\.cli\.main\.display\b` across
  components, bases, and projects, not a symbol-prefix guess): added a
  `[ai.miniforge.cli.main.display.classified-error :as
  classified-error]` require and changed the one call site from
  `display/print-classified-error` to
  `classified-error/print-classified-error`. Its other `display/*`
  calls (`print-info`, `print-error`) are untouched — those symbols
  stayed in `display.clj`.

No test file called any of the moved functions directly (`display_test.clj`
only exercises `print-error`, which did not move), and no
`projects/miniforge/test/` caller referenced the display namespace at
all, so no test call sites needed updating.

This is pure code motion — no detection/formatting logic changed.

## Testing Plan

- `stratum-lint` clean on `display.clj`, `display/classified_error.clj`,
  and `commands/run.clj` (exit 0, was SL003 exit 1 on the original
  `display.clj`).
- `clj-kondo` clean on all three touched/added files.
- `bb pre-commit` green on both commits (commit-budget, poly check,
  lint, stratum-lint, the 18-namespace smoke suite, and the GraalVM
  compatibility suite).
- Direct namespace verification (not relying on `bb test`
  change-scope alone): `clojure -M:dev:test -e "..."` run per affected
  namespace —
  `ai.miniforge.cli.main.display-test` (1 test / 1 assertion),
  `ai.miniforge.cli.main.commands.run-test` (2 tests / 8 assertions),
  `ai.miniforge.cli.workflow-runner.runner-control-wiring-test`,
  `ai.miniforge.cli.main.commands.monitoring-test`,
  `ai.miniforge.cli.main.commands.resume-test`, and
  `ai.miniforge.cli.main.commands.pr-monitor-test` (26 tests / 91
  assertions combined) — 0 failures, 0 errors. All pass.
- Repo-wide grep for the fully-qualified namespace
  (`ai\.miniforge\.cli\.main\.display\b`) across components, bases,
  and projects, and separately for the moved symbol name
  (`print-classified-error`), confirmed exactly one real caller
  (`main/commands/run.clj`), now updated.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file. Other
`bases/cli/main/commands/*.clj` splits in the same batch are tracked
as separate concurrent PRs.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program, `bases/cli` batch. See
  `loader.clj` (miniforge#1772) and `knowledge_safety.clj`
  (miniforge#1731) for the established split convention this follows.

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb pre-commit` green (both commits)
- [x] Direct namespace verification per affected test namespace
- [x] Adversarial self-review: no def added/removed beyond the split,
      no behavior change, `:stratum` metadata recomputed per file
- [x] Zero-fan-in-missed confirmed via fully-qualified namespace grep
      (not symbol-prefix) across components, bases, and projects
