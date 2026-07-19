<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: exceptions-as-data cleanup of self-healing + progress-detector + workflow

## Overview

Migrates 12 throw sites across three components to the canonical
`response/throw-anomaly!` boundary path:

- `components/self-healing/.../stream_recovery.clj` — 4 sites (input validation)
- `components/progress-detector/.../{config,event_envelope,tool_profile}.clj` — 4 sites
- `components/workflow/.../{agent_factory,cost_breakdown}.clj` — 4 sites

Same mechanical shape as Wave 9 + connector cluster cleanups. Each site
gains a typed `:anomaly/category` (`:anomalies/incorrect`,
`:anomalies/unsupported` — the full keywords from the canonical taxonomy
in `components/response/src/.../anomaly.clj`) in ex-data while preserving
the existing `ExceptionInfo` throw contract.

## Motivation

Per the fresh ripgrep of `origin/main` (pre-PR: 13 components × 1–4
sites each, ~46 sites remaining across the mid-tail), these three
components were the highest-leverage bundle: each migrates cleanly to
the standard pattern, and the workflow component already has
`anomaly` + `response` deps declared. Inventory regeneration after
this PR will reflect the closure.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged)
- `ai.miniforge.response` (merged)

## What This Adds / Changes

`components/self-healing/deps.edn`:

- Adds `ai.miniforge/response` dep.

`components/self-healing/src/.../stream_recovery.clj` (4 sites):

- `binary-for` nil backend → `:anomalies/incorrect`.
- `binary-for` non-Named backend → `:anomalies/incorrect`.
- `evaluate-stall-recovery` non-IAtom `:hang-count` → `:anomalies/incorrect`.
- `evaluate-stall-recovery` nil `:backend` → `:anomalies/incorrect`.

`components/progress-detector/deps.edn`:

- Adds `ai.miniforge/response` dep.

`components/progress-detector/src/.../config.clj`:

- `merge-config` empty-layers guard → `:anomalies/incorrect`.

`components/progress-detector/src/.../event_envelope.clj`:

- `validate-observation!` schema-failure → `:anomalies/incorrect`.

`components/progress-detector/src/.../tool_profile.clj`:

- `register!` missing `:tool/id` → `:anomalies/incorrect`.
- `register!` schema-failure → `:anomalies/incorrect`.

`components/workflow/src/.../agent_factory.clj`:

- `create-agent-for-phase` handler-only-agents guard → `:anomalies/incorrect`.
- `create-agent-for-phase` unknown agent-type → `:anomalies/unsupported`.

`components/workflow/src/.../cost_breakdown.clj`:

- `add-phase-cost` unknown phase → `:anomalies/incorrect`.
- `add-phase-cost` iterations-on-non-iter-phase → `:anomalies/incorrect`.

### Tests

New decomposed anomaly tests, one file per component:

- `components/self-healing/test/.../anomaly/stream_recovery_anomaly_test.clj` — 4 tests
- `components/progress-detector/test/.../anomaly/progress_detector_anomaly_test.clj` — 4 tests
- `components/workflow/test/.../anomaly/workflow_factory_cost_anomaly_test.clj` — 4 tests

Each asserts both message + `:anomaly/category`, so tests would fail
if a future refactor reverted to plain `(throw (ex-info ...))`.

## Per-site classification

| File | Site | Category |
|------|------|----------|
| self-healing/stream_recovery.clj | binary-for nil backend | `:anomalies/incorrect` |
| self-healing/stream_recovery.clj | binary-for non-Named backend | `:anomalies/incorrect` |
| self-healing/stream_recovery.clj | evaluate-stall-recovery non-IAtom hang-count | `:anomalies/incorrect` |
| self-healing/stream_recovery.clj | evaluate-stall-recovery nil backend | `:anomalies/incorrect` |
| progress-detector/config.clj | merge-config empty layers | `:anomalies/incorrect` |
| progress-detector/event_envelope.clj | validate-observation! schema fail | `:anomalies/incorrect` |
| progress-detector/tool_profile.clj | register! missing :tool/id | `:anomalies/incorrect` |
| progress-detector/tool_profile.clj | register! schema fail | `:anomalies/incorrect` |
| workflow/agent_factory.clj | create-agent-for-phase handler-only | `:anomalies/incorrect` |
| workflow/agent_factory.clj | create-agent-for-phase unknown type | `:anomalies/unsupported` |
| workflow/cost_breakdown.clj | add-phase-cost unknown phase | `:anomalies/incorrect` |
| workflow/cost_breakdown.clj | add-phase-cost iterations-on-non-iter | `:anomalies/incorrect` |

## Testing Plan

- New `anomaly.*` tests assert message + `:anomaly/category` + key
  context fields.
- Local component-test runs blocked by the well-documented
  `cognitect/test-runner` classpath gap in component-local aliases —
  CI validates.

## Deployment Plan

No migration. ExceptionInfo shape preserved with one extra
`:anomaly/category` key in ex-data. All callers that branch on
`(thrown? ExceptionInfo)` or specific `ex-data` keys continue to work.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Companion to Wave 7/9 + connector cluster cleanup PRs
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)

## Checklist

- [x] All 12 in-scope throw sites migrated
- [x] Single API per site (`response/throw-anomaly!`)
- [x] New decomposed test files (three — one per component)
- [x] No new throws in anomaly-returning code paths
- [x] External caller contracts preserved
- [x] Apache 2 license headers on every new file
- [x] `deps.edn` additions explicit and minimal
