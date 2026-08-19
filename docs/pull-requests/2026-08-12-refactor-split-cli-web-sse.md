<!--
  Title: Split cli/web/sse.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(cli): split web/sse.clj (rule 210)

## Overview

Splits the stream/subscription registry bookkeeping out of
`ai.miniforge.cli.web.sse` into its own sibling namespace,
`ai.miniforge.cli.web.sse.registry`, resolving a stratum-lint SL003
finding (the combined namespace measured 4 real layers, over the rule
210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, bases/cli
batch. `bases/cli/src/ai/miniforge/cli/web/sse.clj` was 90 lines with
4 layers: the `streams`/`subscriptions` atoms and their CRUD
(`get-or-create-stream`, `on-close`, `register!`, `unregister!`,
`get-stream`) formed a cohesive, self-contained layer-group separate
from the httpkit channel wiring (`on-open`, `handle-stream`).

## Changes in Detail

- New file `web/sse/registry.clj`
  (`ai.miniforge.cli.web.sse.registry`): the `streams`/`subscriptions`
  atoms and their CRUD — 2 layers, unchanged behavior.
- `sse.clj`: now only `on-open` and `handle-stream`, requiring
  `registry` for stream lookup/creation and `on-close` — 2 layers.
- `bases/cli/src/ai/miniforge/cli/web.clj`: the three `def` aliases
  (`register-workflow-stream!`, `unregister-workflow-stream!`,
  `get-workflow-stream`) now point at `registry/register!`,
  `registry/unregister!`, `registry/get-stream` instead of `sse/*`;
  the `sse` require is replaced with `sse.registry`.
- `bases/cli/src/ai/miniforge/cli/web/handlers.clj`: unchanged —
  its only use, `sse/handle-stream`, stays in `sse.clj`.
- `bases/cli/test/ai/miniforge/cli/web/sse_test.clj`: updated to
  require `sse.registry` and address `streams`, `subscriptions`,
  `get-stream`, `on-close`, `unregister!` through it; `on-open` calls
  stay through `sse`.

This is pure code motion: no logic changed, only relocated and
re-namespaced. No new indirection/compat shims were added — every
caller was repointed directly at its symbol's new home.

## Testing Plan

- `stratum-lint` clean on `sse.clj`, `sse/registry.clj`, `web.clj`,
  `handlers.clj` (exit 0, was SL003 exit 1 on `sse.clj`).
- `bb lint:clj` clean on all four changed files.
- `clojure -M:poly check` — OK.
- Direct namespace test runs (not relying on `bb test` alone):
  - `ai.miniforge.cli.web.sse-test` — 7 tests, 13 assertions, 0
    failures/errors.
  - `ai.miniforge.cli.web.handlers-test` — 4 tests, 6 assertions, 0
    failures/errors (no direct `sse` references; confirms the
    `web.clj`/`handlers.clj` require changes didn't break anything
    downstream).
- Fan-in confirmed via `grep -rn 'ai\.miniforge\.cli\.web\.sse\b'`
  across `components/`, `bases/`, `projects/`: only `bases/cli/src`
  and `bases/cli/test`; no `projects/miniforge/test` callers.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 remediation program, bases/cli
  batch (see `gh pr diff 1730`, the `policy-pack/builtin_detectors.clj`
  split, for the established convention this follows).

## Checklist

- [x] stratum-lint clean on both resulting files
- [x] `bb lint:clj` clean
- [x] `clojure -M:poly check` OK
- [x] Direct test-namespace runs green (sse-test, handlers-test)
- [x] Fan-in confirmed repo-wide (components + bases + projects)
- [x] Adversarial self-review: def set unchanged, only relocated
