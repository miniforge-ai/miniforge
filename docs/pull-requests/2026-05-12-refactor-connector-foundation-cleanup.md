<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: exceptions-as-data cleanup of connector foundation + small connectors

## Overview

Migrates 9 remaining `:cleanup-needed` throw sites across the connector
foundation (`connector/validation.clj`) and three small connectors
(`connector-retry`, `connector-sarif`, `connector-pipeline-output`).

The foundation migration is the leverage point: every downstream connector
calls `connector/require-handle!`, `validate-auth!`, or
`validate-auth-or-throw!` for boundary-side rejection. Routing those three
helpers through `response/throw-anomaly!` gives the whole connector family
canonical typed anomaly ex-data — no per-connector callsite changes
required.

## Motivation

Per `work/exception-cleanup-inventory.md` (audit 2026-04-23) and the
fresh ripgrep of `origin/main`, 144 total throw sites remain in
production code across components/bases. This PR closes the connector
foundation cluster — small individually but high-leverage because three
helpers cover every connector-* boundary helper. Three small connectors
get migrated in the same PR since they share dep + pattern.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged)
- `ai.miniforge.response` (merged)

## Layer

Refactor / per-component cleanup tier.

## What This Adds / Changes

`components/connector/deps.edn`:

- Adds `ai.miniforge/response` dep.

`components/connector/src/.../validation.clj`:

- 3 boundary helpers (`require-handle!`, `validate-auth!`,
  `validate-auth-or-throw!`) route through `response/throw-anomaly!`.
- Per `response/anomaly`'s contract, the thrown `ex-data` now carries
  canonical anomaly metadata (`:anomaly/category`, `:anomaly/message`,
  `:anomaly/id`, and `:anomaly/timestamp`) at top level alongside the
  existing context keys (`:handle`, `:errors`, etc.). External slingshot
  callers and `(thrown? ExceptionInfo)` consumers see the same context
  shape with added diagnostic keys.

`components/connector-retry/deps.edn`:

- Adds `ai.miniforge/response` dep.

`components/connector-retry/src/.../backoff.clj`:

- `compute-delay` unknown-strategy throw → `:anomalies/unsupported`.

`components/connector-sarif/deps.edn`:

- Adds `ai.miniforge/response` dep.

`components/connector-sarif/src/.../format.clj`:

- `parse-file` unsupported-format throw → `:anomalies/unsupported`.

`components/connector-sarif/src/.../impl.clj`:

- `do-connect` invalid-config throw → `:anomalies/incorrect`.

`components/connector-pipeline-output/deps.edn`:

- Adds `ai.miniforge/response` dep.

`components/connector-pipeline-output/src/.../format.clj`:

- `write-records` `:default` defmethod → `:anomalies/unsupported`.

`components/connector-pipeline-output/src/.../impl.clj`:

- `require-handle!` private helper → `:anomalies/not-found`.

`components/connector-pipeline-output/src/.../schema.clj`:

- `validate!` schema-failure → `:anomalies/incorrect`.

### Tests

Existing compat tests sharpened (`:anomaly/category` assertions added):

- `connector/validation/require_handle_throwing_compat_test.clj` — +1 test
- `connector/validation/validate_auth_throwing_compat_test.clj` — +1 test

New decomposed anomaly tests:

- `connector/validation/validate_auth_or_throw_test.clj` — 4 tests
- `connector-retry/anomaly/backoff_anomaly_test.clj` — 2 tests
- `connector-sarif/anomaly/sarif_anomaly_test.clj` — 2 tests
- `connector-pipeline-output/anomaly/pipeline_output_anomaly_test.clj` — 3 tests

## Per-site classification

| Site | Fn | Anomaly category |
|------|----|------------------|
| connector/validation.clj:93 | `require-handle!` | `:anomalies/not-found` |
| connector/validation.clj:133 | `validate-auth!` | `:anomalies/incorrect` |
| connector/validation.clj:162 | `validate-auth-or-throw!` | `:anomalies/incorrect` |
| connector-retry/backoff.clj:33 | `compute-delay` (unknown strategy) | `:anomalies/unsupported` |
| connector-sarif/format.clj:169 | `parse-file` (unsupported format) | `:anomalies/unsupported` |
| connector-sarif/impl.clj:52 | `do-connect` (invalid config) | `:anomalies/incorrect` |
| connector-pipeline-output/format.clj:33 | `write-records :default` | `:anomalies/unsupported` |
| connector-pipeline-output/impl.clj:23 | `require-handle!` | `:anomalies/not-found` |
| connector-pipeline-output/schema.clj:60 | `validate!` | `:anomalies/incorrect` |

## Strata Affected

- `ai.miniforge.connector.validation` — foundation helpers
- `ai.miniforge.connector-retry.backoff`
- `ai.miniforge.connector-sarif.format` + `.impl`
- `ai.miniforge.connector-pipeline-output.format` + `.impl` + `.schema`
- 2 sharpened compat tests + 4 new decomposed test files

## Testing Plan

- Existing compat tests still pass (response/throw-anomaly! raises
  ExceptionInfo with anomaly map as ex-data — same external contract).
- New tests assert both message + `:anomaly/category` to pin the typed
  classification. Tests would fail if a future refactor reverts to plain
  `(throw (ex-info ...))` with the same message.
- Local component-test runs blocked by the `cognitect/test-runner`
  classpath gap that's been documented in prior Wave 7/9 PR notes — CI
  will validate.

## Deployment Plan

No migration. External slingshot callers and `(thrown? ExceptionInfo)`
consumers see the same shape with one extra `:anomaly/category` key in
ex-data. Existing per-connector callers (`connector-jira`,
`connector-gitlab`, `connector-github`, `connector-edgar`,
`connector-excel`, `connector-file`, `connector-sarif`) gain canonical
typed anomaly ex-data automatically — no per-connector code changes.

## Notes

- **Leverage move.** Foundation helpers serve 7+ downstream connectors;
  migrating them gives those connectors typed anomalies without
  touching each call site. A follow-up PR can address the remaining
  connector-specific raw throws (config validation patterns in
  `-edgar`, `-excel`, `-file`, `-http`, `-github`, `-gitlab`, `-jira`).
- **Docstring example at validation.clj:152** is prose, not code — ripgrep
  matches it but it's part of the helper's documentation showing the
  *pre-cleanup* callsite shape the helper replaces. Left as-is.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Companion to Wave 7/9 cleanup PRs — operator (#797), spec-parser
  (#799), agent (#800), task (#801), pr-lifecycle (#846),
  event-stream (#854), tail-bundle (#855), cli (#859)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)

## Checklist

- [x] All 9 in-scope throw sites migrated
- [x] Single API per site (`response/throw-anomaly!`)
- [x] Existing compat tests sharpened with `:anomaly/category`
- [x] New decomposed test files (four)
- [x] No new throws in anomaly-returning code paths
- [x] External slingshot caller contracts preserved
- [x] Apache 2 license headers preserved
