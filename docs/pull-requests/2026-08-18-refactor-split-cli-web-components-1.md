<!--
  Title: Split cli/web/components.clj (rule 210), part 1/2
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split web/components.clj — add sibling fragment namespaces (1/2)

## Overview

Part 1 of a 2-PR train resolving a stratum-lint SL003 finding on
`bases/cli/src/ai/miniforge/cli/web/components.clj` (7 distinct
layers, over the rule 210 budget of 3). Adds seven new sibling
namespaces under `ai.miniforge.cli.web.components.*`, each holding a
cohesive group of dashboard-fragment functions moved unchanged out of
`components.clj`. This PR only adds files; `components.clj` itself is
untouched and still fails SL003 until PR 2/2 wires these in.

## Motivation

`bases/cli/src/ai/miniforge/cli/web/components.clj` mixes seven
independent concerns (page shell, chat/AI-summary, risk stat cards,
sidebar tree, PR detail header/actions, fleet overview, batch-approve)
in one namespace. Follows the established convention from this
program (`detection.clj`/`loader.clj` 2-PR trains): extract cohesive
layer-groups into sibling files under a subdirectory named after the
original file, each passing the 3-layer budget on its own.

Split into two PRs rather than one because the local pre-commit hook
runs `stratum-lint` on every staged file and hard-blocks a commit
that leaves a touched file over budget. That makes it impossible to
edit `components.clj` incrementally (e.g. extract-and-rewire one
group at a time) the way the `detection.clj` train did — any partial
rewiring of `components.clj` still leaves it over 3 layers until
every extraction lands, so the edit to that file can only be
committed once, atomically, after all seven sibling files already
exist. This PR supplies those seven files first; PR 2/2 is the single
atomic rewrite of `components.clj`.

## Changes in Detail

New files (all pure code motion, no behavior change):

- `components/chat.clj` — AI-summary and chat-panel fragments:
  `chat-message`, `ai-summary`, `ai-summary-error`,
  `ai-summary-placeholder`, `quick-question-buttons` (private),
  `chat-section`. 3 layers.
- `components/pr_stats.clj` — risk/complexity stat-card fragments:
  `risk-label`, `analysis-stats`. 2 layers.
- `components/pr_analysis.clj` — AI-analysis section assembly:
  `recommendation-box` (private), `ai-analysis-section`. Requires
  `pr-stats` and `chat`. 2 layers.
- `components/sidebar.clj` — repo-tree sidebar fragments:
  `sidebar-header`, `repo-pr-item` (private), `repo-group`. 3 layers.
- `components/pr_actions.clj` — PR detail header/action fragments:
  `detail-header`, `action-buttons` (private), `detail-actions`.
  Requires `pr-stats` (for `risk-label`). 3 layers.
- `components/overview.clj` — fleet header and dashboard stat-pill
  fragments: `fleet-header`, `keyboard-hint` (private),
  `keyboard-hints`, `dashboard-stats`. Requires `batch-approve` (for
  `batch-approve-safe-button`) and `status`. 2 layers.
- `components/batch_approve.clj` — batch-approve button and
  fleet-summary banner: `batch-approve-confirm`/`batch-approve-label`
  (private), `batch-approve-safe-button`, `fleet-summary`. 3 layers.

Each new file duplicates the small `t` (message-catalog lookup) and,
where needed, `pr-url` private helpers rather than requiring the
parent `components` namespace back — the same pattern already used by
the existing `components/status.clj` sibling, and necessary here to
avoid a circular dependency (the parent will require these siblings,
not the reverse).

`components.clj` itself is unchanged in this PR (still 7 layers,
SL003 still firing) — cleared in PR 2/2.

## Testing Plan

- `stratum-lint` clean (exit 0) on all seven new files.
- Each new file compiles standalone: `clojure -M:dev:test -e
  "(require 'ai.miniforge.cli.web.components.chat ...)"` — exit 0.
- Full `bb pre-commit` (commit-budget, poly:check, lint:clj,
  lint:stratum, pre-commit smoke tests, GraalVM/Babashka
  compatibility) green on every commit in this PR.
- No behavior change possible to observe yet — nothing requires these
  namespaces until PR 2/2 wires them into `components.clj`.
