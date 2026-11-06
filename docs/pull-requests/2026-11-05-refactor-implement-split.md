<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(phase-software-factory): Split implement.clj under the layer budget

## Overview

`components/phase-software-factory/src/ai/miniforge/phase_software_factory/implement.clj`
was 1,051 lines and used 7 stratum layers (0 to 6) against the rule 210
budget of 3. This PR moves its helpers, unchanged, into three sibling
namespaces. Every resulting file uses 3 layers (0 to 2). Behaviour does
not change.

## Motivation

The pre-commit stratum gate (SL003) blocks any commit that stages a file
over the layer budget. Three pending fixes edit `implement.clj`: the
cancel handling in #2277 and two cost-tracking fixes under #2378. None of
them can be committed until the file is under budget, so the split lands
first, on its own.

## Changes in Detail

The namespaces, all under `ai.miniforge.phase-software-factory`:

1. `implement` keeps the phase entry points and their names:
   `default-config`, `error-implement`, `build-implement-task`,
   `leave-implement`, `enter-implement`, the
   `phase/get-phase-interceptor-method :implement` method and the
   `register-phase-defaults!` call. 6 definitions, no re-exports.
2. `implement-context` (new, 17 definitions) reads the phase's inputs from
   the execution context. That covers the worktree path, files in scope
   and their contents, the context pack and the task base refs. It also
   covers review feedback and handoffs, prior gate and verify failures,
   the streaming callback, peer advice and the backend keyword.
3. `implement-result` (new, 16 definitions) classifies the agent result
   for `leave-implement`. It detects rate limits, network drops, backend
   timeouts, short runs that wrote no files and unverified
   `:already-implemented` claims. It also builds the phase error map and
   the lightweight artifact, consultation and metrics kept in phase
   results.
4. `implement-verify-output` (new, 4 definitions) cuts verify's test output
   for the implementer: failure blocks first, then a head/tail excerpt.

The only edits outside a plain move:

1. Calls that now cross a namespace gained an alias:
   `impl-context/`, `implement-result/` or `verify-output/`.
2. 23 private definitions became public because another namespace now
   calls them. Definitions used only inside their own namespace stay
   private, including the three that tests reach through `#'`.
3. `^{:stratum N}` values and `Layer N` headings were recomputed.
   `stratum-lint --fix` makes no further change to any of the four files.
4. `ns` forms: the three new files have their own, with docstrings.
   `implement` adds the two new siblings it calls. It drops the requires
   it no longer uses: `anomaly`, `task-base`, `messaging`, `repo-index`,
   `schema` and `clojure.string`.
5. No re-exports (rule 008). Nothing outside tests reached a moved var
   through `implement`, so the tests call the new namespaces instead:
   `implement_test.clj` (14 references) and `release_resume_base_test.clj`
   (1). `create-streaming-callback`, `collect-peer-advice` and
   `preserve-consultation`, public before, stay public in their new
   namespaces.
6. `release_worktree.clj`'s docstring names
   `implement-context/base-ref-candidates`.

No `with-redefs` target in the repo points at a moved var, and the one
`::` keyword (`::implement`) stays in `implement`.

Proof of a pure move: the scratchpad `split-compare.clj` compared every
top-level `def*` form of main's `implement.clj` with the four new files.
Its `split-prefix` was `ai.miniforge.phase-software-factory`. It strips
metadata and normalizes sibling-alias references:

```text
base defs 43 | new defs 43 | identical 43 | changed 0 | removed 0 | added 0
```

A second check compared raw text, comments and whitespace included. It
normalized alias prefixes, `defn-` and stratum values. Every top-level
form of main's file then appears exactly once in the new files.

MINIFORGE_PR_BUDGET_OVERRIDE: movement-only rule 210 split of
implement.clj, bodies unchanged.

The move cannot be sliced: the file only drops under the layer budget
when the last group leaves.

## Testing Plan

1. `stratum-lint` (sha `ccde3a1`) reports no findings on the four files.
2. `clj-kondo` reports no errors or warnings on the four files, the same
   as main's `implement.clj`.
3. All 29 `phase-software-factory` test namespaces, plus
   `ai.miniforge.workflow.runner-integration-test`, pass.

## Deployment Plan

No configuration or behaviour change. Nothing to deploy beyond the merge.

## Related Issues/PRs

1. miniforge-ai/miniforge#2277: cancel handling that edits `implement.clj`.
2. miniforge-ai/miniforge#2378: cost tracking, with two fixes that edit
   `implement.clj`.

## Checklist

1. Every definition moved with its body unchanged.
2. Every resulting file is within 3 stratum layers.
3. Every reference to a moved var points at its new namespace.
4. Tests, kondo and stratum-lint pass.
