<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: exceptions-as-data cleanup of cli base

## Overview

Migrates the remaining `:cleanup-needed` throw sites in `bases/cli`:
5 sites in `cli/main/commands/etl.clj` (argument validation) and
1 site in `cli/workflow_runner.clj` (event-stream drain failure).
`workflow_runner.clj` and `workflow_runner/context.clj` were already
substantially migrated to `response/throw-anomaly!` in prior PRs —
only the 1 residual site remained.

## Motivation

Per `work/exception-cleanup-inventory.md` (audit dated 2026-04-23),
`bases/cli` carried 14 `:cleanup-needed` sites. Intervening work (most
of the workflow_runner cleanup) closed 8 of those; the remaining 6 are
addressed here in one PR.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary
- `ai.miniforge.response` (merged) — slingshot `throw-anomaly!`

## Layer

Refactor / per-component cleanup tier.

## What This Adds / Changes

`bases/cli/src/.../main/commands/etl.clj`:

- Adds `ai.miniforge.response.interface` require.
- 5 throw sites migrated to `response/throw-anomaly!`:
  - `resolve-pipeline-path` — empty pack dir (no `pipelines/*.edn`)
    → `:anomalies/not-found`
  - `resolve-pipeline-path` — non-dir non-edn input →
    `:anomalies/incorrect`
  - `resolve-env-path` — missing `--env` arg → `:anomalies/incorrect`
  - `resolve-env-path` — env name not found under `<pack>/envs/`
    → `:anomalies/not-found`
  - `resolve-env-path` — env was a name but pipeline was given
    directly → `:anomalies/incorrect`

`bases/cli/src/.../workflow_runner.clj`:

- 1 throw site migrated. The shutdown-drain failure path (line 840)
  now routes through `response/throw-anomaly!` with `:anomalies/fault`.
  Per `response/anomaly`'s contract, the context map is merged onto
  the thrown anomaly map at the top level (not nested under
  `:anomaly/data`) — so `:reason :event-stream-drain-failed` and the
  full `:drain-result` are top-level keys in `ex-data`. The
  user-facing stderr message remains identical.

`bases/cli/test/.../anomaly/` (new):

- `etl_anomaly_test.clj` — 7 tests covering all 5 etl.clj escalation
  paths plus two happy-path returns through the private fns.

## Per-site classification

| Site (line) | Fn | Anomaly category |
|------------:|----|------------------|
| etl.clj:91 | `resolve-pipeline-path` (empty pack dir) | `:anomalies/not-found` |
| etl.clj:97 | `resolve-pipeline-path` (non-dir non-edn input) | `:anomalies/incorrect` |
| etl.clj:106 | `resolve-env-path` (nil `--env`) | `:anomalies/incorrect` |
| etl.clj:115 | `resolve-env-path` (env name not under pack/envs) | `:anomalies/not-found` |
| etl.clj:118 | `resolve-env-path` (name without pack-dir) | `:anomalies/incorrect` |
| workflow_runner.clj:840 | shutdown drain failure | `:anomalies/fault` |

## Strata Affected

- `ai.miniforge.cli.main.commands.etl` — CLI arg validation
- `ai.miniforge.cli.workflow-runner` — shutdown drain cleanup
- New `ai.miniforge.cli.anomaly.*` test namespace

## Testing Plan

- New `anomaly.*` tests: 7 tests covering both fns' escalation paths
  - happy-path returns.
- CI to confirm full cli suite still green.

## Deployment Plan

No migration. CLI top-level catches both the legacy
`clojure.lang.ExceptionInfo` shape and the new slingshot anomaly
shape uniformly (both surface as `ExceptionInfo`).

## Notes

- **No bb-data-plane-http migration.** Out of scope for this PR — see
  the companion `tail-bundle` PR for the rationale.
- **`bases/cli/deps.edn` not modified.** The cli base sources
  `ai.miniforge/response` and `ai.miniforge/anomaly` transitively
  through the parent projects (`miniforge`, `miniforge-core`,
  `miniforge-tui`, `data-foundry` — all explicitly declare both).
  Same pattern as the existing `response/throw-anomaly!` calls in
  `workflow_runner.clj` already in place pre-PR.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Companion to Wave 7 + 8 cleanup PRs — operator (#797), spec-parser
  (#799), agent (#800), task (#801), pr-lifecycle (#846),
  event-stream (#854), tail-bundle (#855)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)

## Checklist

- [x] All 6 in-scope `:cleanup-needed` cli base sites retired
- [x] Single API per site (`response/throw-anomaly!`)
- [x] Decomposed test file (one — etl.clj surface is small)
- [x] No new throws in anomaly-returning code paths
- [x] External caller contracts preserved
- [x] Apache 2 license headers preserved
