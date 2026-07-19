<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: exceptions-as-data cleanup of VCS connectors (github + gitlab + jira)

## Overview

Migrates 12 throw sites across the three VCS connectors. Same kill-the-
deprecation pattern as PR #869 (foundation) and PR #871 (data sources).

- `connector-github/impl.clj` (3 sites) + `resources.clj` (1)
- `connector-gitlab/impl.clj` (2) + `resources.clj` (1) + `schema.clj` (1)
- `connector-jira/impl.clj` (2) + `resources.clj` (1) + `schema.clj` (1)

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly`
- `ai.miniforge.response`

## What This Adds / Changes

`components/connector-github/deps.edn`, `connector-gitlab/deps.edn`,
`connector-jira/deps.edn`:

- Each gets a `:local/root` dep on `ai.miniforge/response`.

### connector-github (4 sites)

- `impl/require-resource!` unknown resource → `:anomalies/not-found`.
- `impl/validate-connect!` schema-invalid → `:anomalies/incorrect`.
- `impl/validate-connect!` missing owner/org → `:anomalies/incorrect`.
- `resources/load-resources` missing EDN → `:anomalies/not-found`.

### connector-gitlab (4 sites)

- `impl/require-resource!` unknown resource → `:anomalies/not-found`.
- `impl/do-connect` missing project → `:anomalies/incorrect`.
- `resources/load-resources` missing EDN → `:anomalies/not-found`.
- `schema/validate!` validation failure → `:anomalies/incorrect`.

### connector-jira (4 sites)

- `impl/require-resource!` unknown resource → `:anomalies/not-found`.
- `impl/do-connect` missing site → `:anomalies/incorrect`.
- `resources/load-resources` missing EDN → `:anomalies/not-found`.
- `schema/validate!` validation failure → `:anomalies/incorrect`.

### Tests

3 new decomposed anomaly test files (one per connector), each asserting
`:anomaly/category` and selected context fields on the boundary escalation
paths.

## Per-site classification

| File | Site | Category |
|------|------|----------|
| connector-github/impl.clj | require-resource! | `:anomalies/not-found` |
| connector-github/impl.clj | validate-connect! invalid schema | `:anomalies/incorrect` |
| connector-github/impl.clj | validate-connect! missing owner/org | `:anomalies/incorrect` |
| connector-github/resources.clj | load-resources | `:anomalies/not-found` |
| connector-gitlab/impl.clj | require-resource! | `:anomalies/not-found` |
| connector-gitlab/impl.clj | do-connect missing project | `:anomalies/incorrect` |
| connector-gitlab/resources.clj | load-resources | `:anomalies/not-found` |
| connector-gitlab/schema.clj | validate! | `:anomalies/incorrect` |
| connector-jira/impl.clj | require-resource! | `:anomalies/not-found` |
| connector-jira/impl.clj | do-connect missing site | `:anomalies/incorrect` |
| connector-jira/resources.clj | load-resources | `:anomalies/not-found` |
| connector-jira/schema.clj | validate! | `:anomalies/incorrect` |

## Testing Plan

- 3 new decomposed anomaly tests assert `:anomaly/category` and selected
  context fields on every migrated VCS connector escalation path. Tests
  would fail if a covered path reverts to plain `(throw (ex-info ...))`
  with the same context.
- Local component-test runs blocked by the `cognitect/test-runner`
  classpath gap noted in prior PRs — CI validates.

## Deployment Plan

No migration. ExceptionInfo context keys are preserved, with canonical
anomaly metadata (`:anomaly/category`, `:anomaly/message`, `:anomaly/id`,
and `:anomaly/timestamp`) added to ex-data.

## Notes

- **VCS connector cleanup complete after this PR.** Foundation (#869),
  data sources (#871), and VCS (this PR) cover the migrated VCS connector
  throw sites. Remaining `:cleanup-needed` work includes foundation-level
  connector helpers plus workflow residuals, dag-executor, config, gate,
  etc.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Built on PR #869 (connector foundation)
- Built on PR #871 (data-source connectors)
- Companion to Wave 7/9 cleanup PRs
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)

## Checklist

- [x] All 12 in-scope throw sites migrated
- [x] Single API per site (`response/throw-anomaly!`)
- [x] New decomposed test files (three — one per connector)
- [x] No new throws in anomaly-returning code paths
- [x] External slingshot caller contracts preserved
- [x] `deps.edn` additions explicit and minimal
