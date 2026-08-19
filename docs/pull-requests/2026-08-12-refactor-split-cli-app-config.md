<!--
  Title: Split cli/app_config.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split app_config.clj (rule 210)

## Overview

Splits `bases/cli/src/ai/miniforge/cli/app_config.clj` (SL003: 6 distinct
real layers, over the rule-210 budget of 3) into two sibling
implementation namespaces, and turns the original namespace into a thin
re-export facade over them.

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli` batch
(task #25). `ai.miniforge.cli.app-config` has unusually heavy fan-in for a
file this size: 34 files require it directly (fully-qualified) across
`bases/`, `components/pr-lifecycle`, and `projects/miniforge-core`, and
roughly 111 call sites use one of the ten functions that would move to
the "paths" concern. Relocating those call sites would touch dozens of
files for a pure lint fix and — worse — would silently break several
`with-redefs` mocks in caller test files that redefine `app-config/X` and
expect production code elsewhere to observe it through that same Var.
Per the `interface.clj` pattern already codified in
`standards/miniforge/languages/clojure.mdc` ("Interface.clj pattern
(CRITICAL)" — pure pass-through files are exempt from the 3-layer budget)
and the identical precedent in PR #1772
(`policy-pack/interface/loading.clj` re-exporting from
`loader.clj`/`loader/io.clj`), the original namespace stays in place as a
stable facade so no caller needs to change.

## Changes in Detail

- New file `app_config/profile.clj`
  (`ai.miniforge.cli.app-config.profile`): resource-backed CLI identity —
  `app-config-resource`, `default-status-config`, `normalize-profile`
  (private), `getenv` (Layer 0); `app-profile`, `pr-monitor-config`,
  `status-config` (Layer 1); `binary-name`, `display-name`,
  `description`, `system-check-title`, `home-dir-name`, `tui-package`,
  `help-examples` (Layer 2). 3 layers, unchanged behavior.
- New file `app_config/paths.clj` (`ai.miniforge.cli.app-config.paths`):
  home-dir resolution and filesystem layout, built on `profile.clj` —
  `default-home-dir`, `command-string` (Layer 0); `home-dir` (Layer 1);
  `config-path`, `artifacts-dir`, `worktrees-dir`, `events-dir`,
  `logs-dir`, `dashboard-port-file`, `state-file` (Layer 2). 3 layers,
  unchanged behavior.
- `app_config.clj`: now a pure facade — every symbol is a flat `def`
  re-exporting the corresponding var from `profile` or `paths`. All
  re-exports are real Layer 0 (no local interdependency), so the file
  measures 1 real layer, well within budget. Docstrings preserved
  verbatim from the originals.
- `app_config_test.clj`: the two tests that mock the internal composition
  (`getenv` + `default-home-dir` composing into `home-dir`) now require
  `app-config.paths` and `app-config.profile` directly and redefine those
  vars instead of the (now-disconnected) facade copies — `with-redefs` on
  a re-exported `def` does not affect what the moved implementation calls
  internally, since it resolves the real `profile`/`paths` Vars, not the
  facade's. Assertions still call through `app-config/home-dir` etc.,
  unchanged, since those are the same function values.

This is pure code motion: no logic changed, only relocated and
re-namespaced. No caller outside `app_config_test.clj` needed any change
— confirmed by grepping the fully-qualified namespace
`ai\.miniforge\.cli\.app-config\b` across `bases/`, `components/`, and
`projects/` (34 hits, all now covered).

## Testing Plan

- `stratum-lint` clean on all three touched/new source files (exit 0, was
  SL003 exit 1 on `app_config.clj`).
- `clj-kondo` clean on all four touched files.
- `clojure -M:dev:test -e '(run-tests ...)'` on
  `ai.miniforge.cli.app-config-test` directly: 4 tests, 12 assertions, 0
  failures, 0 errors.
- Same, on the nine other `bases/cli` namespaces that call the relocated
  functions (`main-test`, `workflow-runner.display-test`,
  `main.commands.evidence-test`, `main.commands.policy-test`,
  `main.commands.monitoring-test`, `main.commands.pr-monitor-test`,
  `main.commands.workflow-commands-test`, `main.commands.events-test`,
  `main.commands.artifact-cmds-test`): 94 tests, 198 assertions, 0
  failures, 0 errors.
- Same, on the project-level
  `ai.miniforge.workflow.kernel-loader-integration-test` (in
  `projects/miniforge-core/test/`, outside `bb test`'s change-scope): 2
  tests, 16 assertions, 0 failures, 0 errors.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program, `bases/cli` batch (task
  #25).
- Follows the `interface.clj`-facade precedent from PR #1772
  (`policy-pack/loader.clj` split) and the general namespace-splitting
  convention from PR #1730 (`builtin_detectors.clj`) and PRs #1662-#1667
  (`workflow_runner.clj`).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] Real-caller test verification (not just `bb test` change-scope):
      105 tests / 226 assertions across `bases/cli` + project-level, 0
      failures
- [x] Adversarial self-review: def set unchanged, only relocated; facade
      preserves every caller's public API
- [x] Fan-in confirmed repo-wide before starting (34 files; only the
      co-located test needed a change)
- [x] `clj-kondo` clean
