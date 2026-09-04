<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: stratum-lint autofix for the remaining workflow pipeline test namespaces

## Overview

Runs `stratum-lint --fix` (sha `ccde3a1182a3c68e6579a10bcc18506db3a5e469`,
the pin in `tasks/stratum.clj`) over the four test namespaces that run
`runner/run-pipeline` and still carried no `^{:stratum n}` metadata or
`Layer N` headings after #1889:

- `components/workflow/test/.../context_duration_test.clj`
- `components/workflow/test/.../runner_environment_test.clj`
- `projects/miniforge/integration/.../meta_agent_test.clj`
- `projects/miniforge/e2e/.../meta_agent_e2e_test.clj`

Headings, metadata, and def order only. Diff stat: 4 files changed, 196 insertions(+), 196 deletions(-).

This branch originally covered ten namespaces. #1889 landed the same fixer
output for the other six (`runner_extended_test`, `runner_iteration_test`,
`run7_regression_test`, `environment_promotion_integration_test`,
`anomaly/build_initial_context_test`, project `runner_integration_test`)
byte-for-byte identically, so they dropped out on merging `main`.

## Motivation

The next PR registers a `:once` fixture in each of these namespaces so
their pipelines stop acquiring worktrees from the checkout the test JVM
was launched in. The pre-commit hook autofixes every fully-staged Clojure
file, so any six-line edit to an unannotated file arrives as a full
rewrite. Landing the rewrite on its own keeps that PR reviewable at its
real size and keeps this one skimmable: the same mechanical wave as #1889
and the 2026-07-25/26 stratum-lint PRs.

## Verification

- Per file, the multiset of non-blank lines with `^{:stratum n}` and
  `Layer N` headings stripped is identical before and after. One
  exception was corrected by hand: the fixer moved the trailing comment
  `; 2 minute total timeout` in `meta_agent_e2e_test.clj` from the
  workflow def it annotates onto the `use-fixtures` form; it is back on
  the def.
- `brick:workflow` with these rewrites applied: 1894 tests, 0 failures.
- `meta-agent-test` and `meta-agent-e2e-test` run directly from
  `projects/miniforge`: 13 failures, all in `meta-agent-test` and all
  present at `main` before any change (every pipeline there finishes
  `:failed`).

## Commit budget

Over the 200-line commit budget and the 600-line PR budget by construction;
both overridden with the rationale that this is fixer output. The
follow-up PR is within budget.

MINIFORGE_PR_BUDGET_OVERRIDE: stratum-lint --fix output only (rule 210 wave); no hand edits beyond one displaced comment
