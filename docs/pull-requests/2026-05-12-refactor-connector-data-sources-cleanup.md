# refactor: exceptions-as-data cleanup of data-source connectors

## Overview

Migrates 19 throw sites across four data-source connectors:
`connector-edgar`, `connector-excel`, `connector-file`, `connector-http`.
Same mechanical shape as the connector-foundation PR — every site routes
through `response/throw-anomaly!` with a typed `:anomaly/category` in
ex-data.

## Motivation

After the foundation PR landed canonical anomaly throws in
`connector/validation.clj`, the per-connector files still carried raw
`(throw (ex-info ...))` sites. This PR closes the data-source cluster.
VCS connectors (`-github`, `-gitlab`, `-jira`) are a separate follow-up.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly`
- `ai.miniforge.response`

## Layer

Refactor / per-component cleanup tier.

## What This Adds / Changes

`components/connector-edgar/deps.edn` + `connector-excel/deps.edn` +
`connector-file/deps.edn` + `connector-http/deps.edn`:

- Each gets a `:local/root` dep on `ai.miniforge/response`.

`connector-edgar/src/.../impl.clj` (4 sites):

- `do-connect` missing form-type/user-agent/aggregation →
  `:anomalies/incorrect`.
- `do-extract` unknown aggregation → `:anomalies/unsupported`.

`connector-excel/src/.../impl.clj` (5 sites):

- `parse-sheet` sheet-not-found → `:anomalies/not-found`.
- `download-to-temp` non-2xx HTTP → `:anomalies/unavailable`.
- `do-connect` missing url/sheet-name/columns → `:anomalies/incorrect`.

`connector-file/src/.../impl.clj` (4 sites):

- `do-connect` missing path/format → `:anomalies/incorrect`.
- `do-connect` unsupported format → `:anomalies/unsupported`.
- `do-extract` file-not-found → `:anomalies/not-found`.

`connector-http/src/.../impl.clj` (5 sites) + `request.clj` (1 site):

- `do-connect` missing base-url/endpoint → `:anomalies/incorrect`.
- `do-discover` / `do-extract` handle-not-found → `:anomalies/not-found`.
- `fetch-single` request failure → `:anomalies/unavailable`.
- `request/throw-on-failure!` → `:anomalies/unavailable`, preserving the
  legacy `:error-type` key in ex-data so existing callers branching on
  `:error-type` keep working.

### Tests

New decomposed anomaly tests, one per connector:

- `connector-edgar/test/.../anomaly/edgar_anomaly_test.clj`
- `connector-excel/test/.../anomaly/excel_anomaly_test.clj`
- `connector-file/test/.../anomaly/file_anomaly_test.clj`
- `connector-http/test/.../anomaly/http_anomaly_test.clj`

Together they assert `:anomaly/category` and key context fields across the
main boundary escalation families. They intentionally cover representative
paths per connector rather than every migrated throw site.

## Per-site classification

| File | Site | Category |
|------|------|----------|
| connector-edgar/impl.clj | do-connect missing form-type | `:anomalies/incorrect` |
| connector-edgar/impl.clj | do-connect missing user-agent | `:anomalies/incorrect` |
| connector-edgar/impl.clj | do-connect missing aggregation | `:anomalies/incorrect` |
| connector-edgar/impl.clj | do-extract unknown aggregation | `:anomalies/unsupported` |
| connector-excel/impl.clj | parse-sheet sheet-not-found | `:anomalies/not-found` |
| connector-excel/impl.clj | download-to-temp non-2xx | `:anomalies/unavailable` |
| connector-excel/impl.clj | do-connect missing url | `:anomalies/incorrect` |
| connector-excel/impl.clj | do-connect missing sheet-name | `:anomalies/incorrect` |
| connector-excel/impl.clj | do-connect missing columns | `:anomalies/incorrect` |
| connector-file/impl.clj | do-connect missing path | `:anomalies/incorrect` |
| connector-file/impl.clj | do-connect missing format | `:anomalies/incorrect` |
| connector-file/impl.clj | do-connect unsupported format | `:anomalies/unsupported` |
| connector-file/impl.clj | do-extract file-not-found | `:anomalies/not-found` |
| connector-http/impl.clj | do-connect missing base-url | `:anomalies/incorrect` |
| connector-http/impl.clj | do-connect missing endpoint | `:anomalies/incorrect` |
| connector-http/impl.clj | do-discover handle-not-found | `:anomalies/not-found` |
| connector-http/impl.clj | fetch-single request failure | `:anomalies/unavailable` |
| connector-http/impl.clj | do-extract handle-not-found | `:anomalies/not-found` |
| connector-http/request.clj | throw-on-failure! | `:anomalies/unavailable` |

## Strata Affected

- 4 connector `impl.clj` files + `connector-http/request.clj`
- 4 new decomposed anomaly test files

## Testing Plan

- New `anomaly.*` tests assert `:anomaly/category` and key context fields
  across representative connector escalation paths. Tests would fail if a
  covered path reverts to plain `(throw (ex-info ...))` with the same
  message.
- Local component-test runs blocked by the `cognitect/test-runner`
  classpath gap noted in prior PRs — CI validates.

## Deployment Plan

No migration. ExceptionInfo shape preserved with one extra
`:anomaly/category` key in ex-data. The `connector-http/request.clj`
`throw-on-failure!` continues to carry `:error-type` in ex-data, so
existing callers branching on that key are unaffected.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Built on PR #869 (connector foundation cleanup)
- Companion to Wave 7/9 cleanup PRs
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)

## Checklist

- [x] All 19 in-scope throw sites migrated
- [x] Single API per site (`response/throw-anomaly!`)
- [x] New decomposed test files (four — one per connector)
- [x] No new throws in anomaly-returning code paths
- [x] External slingshot caller contracts preserved
- [x] `request/throw-on-failure!` `:error-type` key retained
- [x] `deps.edn` additions explicit and minimal
