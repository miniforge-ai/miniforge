<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: exceptions-as-data cleanup of pr-lifecycle

## Overview

Migrates the 5 `:cleanup-needed` throw sites in
`components/pr-lifecycle/src/.../controller.clj` (3) and
`components/pr-lifecycle/src/.../responder.clj` (2) to anomaly-returning
canonical fns. Boundary throws inlined via `response/throw-anomaly!` so
external slingshot consumers (PR lifecycle orchestrator, top-level CLI
dispatch) keep their thrown-exception contract.

## Motivation

Per `work/exception-cleanup-inventory.md`, `pr-lifecycle` had 5
`:cleanup-needed` sites. Same kill-the-deprecation shape as the workflow
and Wave 7 cleanups (PRs #777, #797, #799, #800, #801) — applied here
directly without redesign.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary
- `ai.miniforge.response` (merged) — slingshot `throw-anomaly!`

## Layer

Refactor / per-component cleanup tier.

## What This Adds / Changes

`components/pr-lifecycle/deps.edn`:

- Adds `:local/root` deps on `ai.miniforge/anomaly` and
  `ai.miniforge/response`.

`components/pr-lifecycle/src/.../controller.clj`:

- New canonical anomaly-returning fns (Layer 0):
  - `iter-budget-result` — `:ok | :conflict anomaly` (replaces the two
    `(>= current max)` thrown-exception sites in `handle-ci-failure!` /
    `handle-review-feedback!`)
  - `pr-creation-result` — `:ok | :fault anomaly` (replaces the
    `dag/err?` thrown-exception site in `run-lifecycle!`)
- Boundary escalation in the three callers via
  `(response/throw-anomaly! :anomalies/conflict ...)` and
  `(response/throw-anomaly! :anomalies/fault ...)` — preserves
  `clojure.lang.ExceptionInfo`-shaped throw for legacy
  `thrown-with-msg?` consumers.

`components/pr-lifecycle/src/.../responder.clj`:

- New canonical anomaly-returning fns (Layer 0 / Layer 4):
  - `parse-pr-url-result` — `parsed map | :invalid-input anomaly`
    (replaces the inline `(throw (ex-info "Could not parse PR number
    from URL" ...))`)
  - `fetch-actionable-comments-result` — `{:comments :groups} | :fault
    anomaly` (replaces the inline `(throw (ex-info "Failed to fetch PR
    comments" ...))`)
- Private boundary helper `fetch-actionable-comments` calls the
  anomaly-returning fn and rethrows via `:anomalies/fault` for the
  in-component caller `respond-to-comments!`.
- `respond-to-comments!` consumes `parse-pr-url-result` directly and
  rethrows via `:anomalies/incorrect` on URL-parse anomaly.

`components/pr-lifecycle/test/.../anomaly/` (new):

- `iter_budget_result_test.clj` — 7 tests covering
  iter-budget-result happy path, anomaly path, zero-budget edge case,
  and boundary escalation through `handle-ci-failure!` /
  `handle-review-feedback!` plus state-transition + history side
  effects.
- `pr_creation_result_test.clj` — 3 tests covering happy path, DAG err
  → `:fault` anomaly, and `:anomaly/data` carrying the underlying error
  detail.
- `parse_pr_url_result_test.clj` — 5 tests covering valid URL parse,
  non-github URL, malformed URL, nil, and boundary escalation through
  `respond-to-comments!`.
- `fetch_actionable_comments_result_test.clj` — 4 tests covering
  successful fetch with bot-comment filtering, poller `dag/err`
  yielding `:fault` anomaly, and stable anomaly message.

## Per-site classification

| Site (line) | Old throw | Anomaly type | Boundary throw category |
|------------:|-----------|--------------|--------------------------|
| controller.clj:388 | `handle-ci-failure!` (max-iter) | `:conflict` (via `iter-budget-result`) | `:anomalies/conflict` |
| controller.clj:420 | `handle-review-feedback!` (max-iter) | `:conflict` (via `iter-budget-result`) | `:anomalies/conflict` |
| controller.clj:521 | `run-lifecycle!` (PR-creation DAG err) | `:fault` (via `pr-creation-result`) | `:anomalies/fault` |
| responder.clj:200 | `fetch-actionable-comments` (poller err) | `:fault` (via `fetch-actionable-comments-result`) | `:anomalies/fault` |
| responder.clj:243 | `respond-to-comments!` (URL parse) | `:invalid-input` (via `parse-pr-url-result`) | `:anomalies/incorrect` |

Boundary throws use the **general** slingshot categories
(`:anomalies/conflict`, `:anomalies/fault`, `:anomalies/incorrect`) —
adding a `:anomalies.pr-lifecycle/*` set would touch the response
component, which is out of scope here.

## Strata Affected

- `ai.miniforge.pr-lifecycle.controller` — iter-budget + PR-creation
  cleanup
- `ai.miniforge.pr-lifecycle.responder` — URL-parse + comment-fetch
  cleanup
- New `ai.miniforge.pr-lifecycle.anomaly.*` test namespaces

## Testing Plan

- `clojure -M:test -m cognitect.test-runner -d test` from
  `components/pr-lifecycle`: **319 tests / 2048 passes / 0 failures /
  0 errors**.
- New `anomaly.*` files: 20 tests / 45 assertions, all green.
- Existing `controller_test.clj` `thrown-with-msg?
  clojure.lang.ExceptionInfo` assertions continue to pass —
  `response/throw-anomaly!` raises `ExceptionInfo` with the anomaly
  message preserved as `(.getMessage ex)`.
- Lint: clean.

## Deployment Plan

No migration. External slingshot callers continue to see the same
`ex-info` shapes via the boundary throws (`:anomalies/conflict`,
`:anomalies/fault`, `:anomalies/incorrect`).

## Notes

- **Same shape as Wave 7 PRs.** `iter-budget-result` mirrors
  `task/transition-result`; `pr-creation-result` mirrors
  `agent/parsed-plan-or-anomaly`; the responder split mirrors
  `task/lookup-task` + `lookup-task!`. Saves design time.
- **No new i18n keys.** Inline anomaly messages for the responder
  sites use the existing inline strings — adding messages/t entries
  would be an unrelated change.

## Related Issues/PRs

- Built on PR #777 (FSM-transition cleanup precedent)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)
- Companion to Wave 7 cleanup PRs — operator (#797), spec-parser
  (#799), agent (#800), task (#801)

## Checklist

- [x] All 5 `:cleanup-needed` pr-lifecycle sites retired
- [x] Kill-the-deprecation pattern from PR #777 applied
- [x] Single API per site
- [x] Boundary throws inlined at the three caller sites
- [x] External caller contracts preserved (`thrown-with-msg?
      ExceptionInfo` tests still pass)
- [x] Decomposed test files (four)
- [x] No new throws in anomaly-returning code paths
- [x] `pr-lifecycle` full test suite passes (319 tests / 2048
      assertions)
- [x] Apache 2 license headers preserved
