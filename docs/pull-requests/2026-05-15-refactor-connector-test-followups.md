<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: connector cleanup test coverage follow-ups

## Overview

Adds the test coverage that PR #871 (data-source connectors) review
flagged as missing. The migrated throw sites were correct but lacked
direct test coverage, so a future regression could go undetected.

Scope note: #874 (VCS connectors) had similar review feedback, but the
auto-fix pass that landed before #874 merged already added direct tests
for every flagged VCS path (`do-connect-invalid-schema-throws-anomaly`,
`load-resources-missing-edn-throws-anomaly` × 3, etc.). The remaining
gaps are #871-only.

## What This Adds

`components/connector-excel/test/.../anomaly/excel_anomaly_test.clj`:

- `parse-sheet-missing-sheet-throws-anomaly` — constructs an
  `XSSFWorkbook` with one sheet, asks for a different one, asserts
  `:anomalies/not-found` and `:sheet` in ex-data.
- `do-connect-non-2xx-download-throws-anomaly` — redefs
  `babashka.http-client/get` to return `:status 503`, asserts
  `:anomalies/unavailable` and `:status 503` in ex-data.

`components/connector-edgar/test/.../anomaly/edgar_anomaly_test.clj`:

- `do-extract-unknown-aggregation-throws-anomaly` — connects with a
  bogus `:edgar/aggregation` value, calls `do-extract`, asserts
  `:anomalies/unsupported` and the aggregation value in ex-data.

`components/connector-http/test/.../anomaly/http_anomaly_test.clj`:

- `fetch-single-request-failure-throws-anomaly` — connects, redefs
  `impl/do-request` to return a failure result, asserts
  `:anomalies/unavailable` and `:error-type :transient` in ex-data.

## Why

PR #871 received review feedback that several escalation paths listed
in the per-site classification table had no direct test coverage.
Specifically:

- excel `parse-sheet` not-found path
- excel `download-to-temp` non-2xx path
- edgar `do-extract` unknown-aggregation path
- http `fetch-single` request-failure path

The #871 PR doc claimed "every escalation path" was covered, which
wasn't strictly true for these four sites. This PR closes those gaps
and removes the regression-risk window.

PR #874 had similar feedback but the auto-fix pass already added the
missing VCS tests pre-merge; no additional VCS coverage is needed.

## Testing Plan

- 4 new test cases, each asserting `:anomaly/category` and a key
  context field on the migrated throw path.
- Existing test suites unaffected.

## Related Issues/PRs

- Follow-up to PR #871 (data-source connectors)
- PR #874 (VCS connectors) gaps were closed by the pre-merge auto-fix
  pass; referenced here for completeness only
- Part of the exceptions-as-data cleanup tracked in PR #691

## Checklist

- [x] Each previously-flagged escalation path now has a direct test
- [x] Tests assert `:anomaly/category` + key context field, not just
      message
- [x] Apache 2 license headers preserved on all edited files
