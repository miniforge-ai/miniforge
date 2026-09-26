<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(workflow): split execution.clj under the layer budget

## Overview

`components/workflow/src/ai/miniforge/workflow/execution.clj` measured six
strata; SL003 allows three, so every commit touching it needed
`MINIFORGE_STRATUM_BUDGET_MODE=warn`. It is now six sibling namespaces
plus a one-stratum `execution.clj` that keeps `execute-phase-step`.

## Motivation

Pre-existing debt. Nothing could touch the file without the warn escape
hatch. The split follows the seams already in the file: phase lifecycle,
gate validation plus phase transition, and DAG integration. DAG
integration needed three namespaces, because two of its internal chains
were each five levels deep.

## Changes in Detail

| namespace | holds | strata |
|---|---|---|
| `execution` | `execute-phase-step` | 1 |
| `execution-lifecycle` | enter -> gates -> leave; recording the result into ctx | 3 |
| `execution-transition` | gate validation; result -> event; event -> machine | 3 |
| `execution-dag` | DAG activation after the plan phase, success/failure folding, skip diagnostic | 3 |
| `execution-dag-sync` | copying sub-worktree changes into the parent worktree | 3 |
| `execution-result-summary` | `classify-output`, `summarize-error` (keys-only summaries) | 3 |
| `execution-events` | stream/workflow-id lookup and the four event publishers | 2 |

The require graph has no cycles:

1. `execution` requires `execution-lifecycle`, `execution-transition`
   and `execution-dag`.
2. `execution-dag` requires `execution-transition`, `execution-dag-sync`,
   `execution-result-summary` and `execution-events`.
3. `execution-lifecycle` requires `execution-transition`.
4. `execution-transition` requires `execution-events`.

Seven private fns became public because their callers now sit in another
namespace: the four `emit-*!` publishers, `merge-sub-worktree-changes!`,
`classify-output` and `summarize-error`.

Callers were updated rather than given re-export shims in `execution`.
Each test now requires the namespace that owns the function it calls.
`runner.clj`, the only production caller, uses `execute-phase-step` and
needs no change.

Two functions with no production caller were removed rather than moved
(rule 008):

1. `index-after-phase`: unreferenced since #638.
2. `dag-applicable?`: kept as a backward-compatible alias when
   `dag-skip-reason` replaced it. Only its own test called it, and
   `skip-reason-disabled` already covers that test's
   `:disable-dag-execution` case.

A `dag_task_execution.clj` comment that named `execution.clj` as an
`:execution/artifacts` writer now names the two files that write it.
`dag_orchestrator.clj`'s namespace docstring still lists `execution.clj`
among the callers of `execute-plan-as-dag` (now `execution_dag.clj`). That
file is itself over SL003 on main, so it cannot be edited here.

## Testing Plan

Behaviour preservation was checked form by form, not just asserted:

1. **By name.** Of the 44 top-level forms in the original file, 42 exist
   after the split. The two missing are the removed pair above. No form
   was added.
2. **By body.** Each form was read with the Clojure reader and compared
   to the original after normalising what a split may change: alias
   qualification of calls into sibling namespaces, `defn-` to `defn`,
   stratum tags and `#()` gensyms. All 42 match.
3. **Comments.** Every comment line in the original survives, apart from
   the non-canonical `Layer 1.5: DAG integration helpers` heading.
4. **Tests.** All 71 `components/workflow` test namespaces: 629 tests,
   2036 assertions, 0 failures. `artifact-persistence-test`
   (projects/miniforge): 9 tests, 21 assertions, 0 failures.
5. **Lint.** `stratum-lint` is clean on every touched file, and `--fix`
   rewrites none of them. clj-kondo reports 0 errors and 0 warnings.

## Commits

Extraction first, flip last, so no commit staged an over-budget file:

1. Four commits, each adding new namespaces unused and copied verbatim.
2. Tests re-pointed at the new namespaces.
3. The flip: `execution.clj` reduced to `execute-phase-step`. This commit
   is over the 200-line commit budget (674 reportable, nearly all
   deletions). It used `MINIFORGE_COMMIT_BUDGET_OVERRIDE`, because any
   partial deletion leaves the file over SL003.

## Related Issues/PRs

- #1922: brings the four affected test files under the layer budget.
  This PR is stacked on it.
- #1485, #1843: earlier splits under the same rule.

## Checklist

- [x] Form set identical before and after, except the two removed dead fns
- [x] Form bodies unchanged beyond qualification, visibility and stratum tags
- [x] Every touched file at three strata or fewer and a `--fix` fixed point
- [x] No re-export shims; callers point at the owning namespace
