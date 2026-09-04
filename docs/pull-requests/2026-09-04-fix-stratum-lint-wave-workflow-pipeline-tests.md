<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: stratum-lint autofix for the workflow pipeline test namespaces

## Overview

Runs `stratum-lint --fix` (sha `ccde3a1182a3c68e6579a10bcc18506db3a5e469`,
the pin in `tasks/stratum.clj`) over the ten test namespaces that run
`runner/run-pipeline` and carried no `^{:stratum n}` metadata or
`Layer N` headings: eight in `components/workflow/test`, one in
`projects/miniforge/test`, one each in `projects/miniforge/integration`
and `projects/miniforge/e2e`. Headings, metadata, and def order only.

Diff stat: 10 files changed, 593 insertions(+), 606 deletions(-). Deletions exceed insertions by
the blank lines the fixer drops (a leading blank after the license header,
doubled blanks between forms).

## Motivation

The next PR registers a `:once` fixture in each of these namespaces so
their pipelines stop acquiring worktrees from the checkout the test JVM
was launched in. The pre-commit hook autofixes every fully-staged Clojure
file, so any six-line edit to an unannotated file arrives as a full
rewrite. Landing the rewrite on its own keeps that PR reviewable at its
real size (~170 reportable lines) and keeps this one skimmable: it is the
same mechanical wave as the 2026-07-25/26 stratum-lint PRs.

## Verification

- Per file, the multiset of non-blank lines with `^{:stratum n}` and
  `Layer N` headings stripped is identical before and after. One
  exception was corrected by hand: the fixer moved the trailing comment
  `; 2 minute total timeout` in `meta_agent_e2e_test.clj` from the
  workflow def it annotates onto the `use-fixtures` form; it is back on
  the def.
- `brick:workflow` with these rewrites applied (measured on the stacked
  branch, which adds only the fixture lines): 1894 tests, 0 failures.
- `runner-integration-test`, `dag-orchestrator-test`,
  `opsv-lifecycle-integration-test`, `meta-agent-test`,
  `meta-agent-e2e-test` run directly from `projects/miniforge`: 73 tests,
  13 failures, all in `meta-agent-test` and all present at `main` before
  any change (every pipeline there finishes `:failed`).

## Commit budget

Over the 200-line commit budget and the 600-line PR budget by construction;
both overridden with the rationale that this is fixer output. The
follow-up PR is within budget.

MINIFORGE_PR_BUDGET_OVERRIDE: stratum-lint --fix output only (rule 210 wave); no hand edits beyond one displaced comment
