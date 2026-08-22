<!--
  Title: Split cli/web/components/status.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split web/components/status.clj (rule 210)

## Overview

Splits the per-run workflow list rendering out of
`ai.miniforge.cli.web.components.status` into a new sibling namespace,
`ai.miniforge.cli.web.components.status.workflow-runs`, resolving a
stratum-lint SL003 finding (the combined namespace measured 4 real
layers, over the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, `bases/cli`
batch. `status.clj`'s call graph has a genuine 4-deep chain:
`workflow-status-icon` (leaf) -> `workflow-run` (renders one row,
calls the icon fn) -> `workflow-runs` (renders the list, calls
`workflow-run`) -> `workflow-status` (the widget, calls `workflow-runs`).
No amount of relabeling collapses that to 3 layers in one file; the
chain has to be split across a namespace boundary.

`ai.miniforge.cli.web.components.status/workflow-status-icon` is the
only symbol from this namespace consumed outside it (re-exported by
`web/components.clj`, itself the sole fan-in point for this
namespace repo-wide — confirmed via a fully-qualified-namespace grep
across `components/`, `bases/`, and `projects/`), so the split had to
preserve that name resolving from the original namespace without
introducing a require cycle between the two files.

## Changes in Detail

- New file `status/workflow_runs.clj`
  (`ai.miniforge.cli.web.components.status.workflow-runs`): the
  icon -> run -> runs composition chain (`no-workflows-style`,
  `workflow-status-icon`, `workflow-run`, `workflow-runs`) — 3 layers.
  `workflow-runs` changes from `defn-` to `defn` (now consumed across
  the namespace boundary by `status.clj`'s `workflow-status`); no
  other visibility or behavior changes.
- `status.clj`: keeps `status-indicator` and `workflow-status` (the
  two externally-relevant widgets) plus their shared leaf helpers
  (`t`, `overall-status-key`, `workflow-stat`) — 2 layers. Requires
  the new `workflow-runs` namespace for `workflow-run`s' rendering and
  re-exports `workflow-status-icon` as a passthrough `def` so
  `ai.miniforge.cli.web.components.status/workflow-status-icon` still
  resolves for `web/components.clj` unchanged.

This is pure code motion plus one necessary `defn-` -> `defn`
visibility change and one passthrough re-export `def`: no logic
changed.

Note: `bases/cli/src/ai/miniforge/cli/web/components.clj` (task #24)
is being split concurrently by a separate agent and is not touched by
this PR.

## Testing Plan

- `stratum-lint` clean on both files (exit 0, was SL003 exit 1 on the
  original).
- Namespaces compile clean from the full workspace
  (`projects/miniforge`), including the unmodified `web/components.clj`
  facade that re-exports the moved symbols.
- `ai.miniforge.cli.web.components-test` and
  `ai.miniforge.cli.web.handlers-test` (the tests exercising
  `status-indicator`, `workflow-status`, and `workflow-status-icon`
  through the `web/components.clj` facade and the HTTP handlers) run
  directly via `clojure.test/run-tests`: 9 tests, 23 assertions, 0
  failures, 0 errors.
- No `projects/miniforge/test/` file references this namespace
  directly (checked by grep) — no project-level test gap to cover.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 program, `bases/cli` batch (task
  #29). See PR #1730 (`policy-pack/builtin_detectors.clj`) for the
  established single-sibling split convention this follows, and the
  `workflow_runner/preflight_probe.clj` + `preflight_support.clj` pair
  for precedent on breaking a multi-file dependency chain across a
  namespace boundary without introducing a require cycle.

## Checklist

- [x] stratum-lint clean on both resulting files
- [x] Full-workspace compile check green, including the dependent
      `web/components.clj` facade
- [x] Direct `clojure.test/run-tests` green (9 tests / 23 assertions)
- [x] Adversarial self-review: def set unchanged except one
      `defn-` -> `defn` visibility change and one re-export `def`
- [x] Fan-in confirmed repo-wide before starting (single caller:
      `web/components.clj`, itself untouched)
