# refactor: foundation-tier exceptions-as-data cleanup

## Overview

First cleanup PR for the `005 exceptions-as-data` discipline. Per the recommended ordering in `work/exception-cleanup-inventory.md`, this PR addresses the foundation tier — high-leverage helpers that every downstream component eventually calls. Three scopes covered: `schema/validate`, `dag-primitives/unwrap`, and connector-core validation helpers.

Each scope adds an anomaly-returning variant alongside the existing throwing function. The throwers are preserved (and now delegate to the anomaly variant) so existing callers see no behavior change.

## Motivation

Foundation helpers throw on bad input today. Every caller wraps in try/catch, or worse, lets the exception unwind past structured information that should have stayed with the failure. Per the new standards rule, these foundation helpers should expose anomaly-returning variants so downstream code can compose with response-chains and evidence records cleanly.

This is the "high leverage" tier per the inventory — converting these unblocks ~38 follow-on cleanup sites in connector components alone.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — provides the canonical anomaly shape and constructor
- `005 exceptions-as-data` standards rule (merged) — prescribes the discipline

## Layer

Refactor / foundation tier. Three independent commits, one per scope, can be reviewed individually.

## What This Adds / Changes

### Scope 1 — `schema/validate` (commit `cc13aac5`)

- Modified `components/schema/deps.edn` — `:local/root` dep on `ai.miniforge/anomaly`
- Modified `components/schema/src/ai/miniforge/schema/interface.clj`:
  - New `validate-anomaly` — returns the validated value or an `:invalid-input` anomaly
  - Existing `validate` is now deprecated; delegates to `validate-anomaly` and translates anomalies back into `ex-info` for backward compat
- New decomposed tests:
  - `interface/validate_anomaly_happy_path_test.clj`
  - `interface/validate_anomaly_failure_test.clj`
  - `interface/validate_throwing_compat_test.clj`

### Scope 2 — `dag-primitives/unwrap` (commit `9d7eb1c0`)

- Modified `components/dag-primitives/deps.edn` — `:local/root` dep on `ai.miniforge/anomaly`
- Modified `components/dag-primitives/src/ai/miniforge/dag_primitives/result.clj`:
  - New `unwrap-anomaly` — returns the unwrapped value or a `:fault` anomaly. Original `:code` (e.g. `:cycle-detected`) is preserved in `:anomaly/data :code` for callers that pattern-match on it.
  - Existing `unwrap` is now deprecated; delegates and re-throws via the original `ex-info` shape.
- Modified `components/dag-primitives/src/ai/miniforge/dag_primitives/interface.clj` — exports `unwrap-anomaly`
- New decomposed tests:
  - `unwrap_anomaly_happy_path_test.clj`
  - `unwrap_anomaly_failure_test.clj`
  - `unwrap_throwing_compat_test.clj`

### Scope 3 — connector-core extraction (commit `81a5ac00`)

The recurring `require-handle!` / `validate-auth!` patterns that each connector component (`connector-jira`, `connector-gitlab`, `connector-github`, `connector-excel`, …) reimplemented are extracted into a shared helper in `connector` core. Both throwing and anomaly-returning variants are provided.

- Modified `components/connector/deps.edn` — `:local/root` dep on `ai.miniforge/anomaly`
- New `components/connector/src/ai/miniforge/connector/validation.clj`:
  - `require-handle` (anomaly) / `require-handle!` (throws) — `:not-found` if the handle is absent
  - `validate-auth` (anomaly) / `validate-auth!` (throws) — `:invalid-input` if auth shape is malformed
- Modified `components/connector/src/ai/miniforge/connector/interface.clj` — exports all four
- 6 decomposed test files under `components/connector/test/ai/miniforge/connector/validation/`

**Per-connector callsite migration is NOT in this PR.** Each connector currently inlines its own validation logic; migrating their callsites to the new shared helpers is a follow-on workstream. ~38 inventory `:cleanup-needed` sites across the connector family will be addressed by those follow-ons.

## New API surface

```
ai.miniforge.schema.interface/validate-anomaly        → value | :invalid-input anomaly
ai.miniforge.dag-primitives.interface/unwrap-anomaly  → data  | :fault anomaly
ai.miniforge.connector.interface/require-handle       → state | :not-found anomaly
ai.miniforge.connector.interface/validate-auth        → nil   | :invalid-input anomaly
ai.miniforge.connector.interface/require-handle!      → state | throws (deprecated, delegates)
ai.miniforge.connector.interface/validate-auth!       → nil   | throws (deprecated, delegates)
```

## Inventory delta

Inventory totals: 158 cleanup-needed sites repo-wide.

- **Directly retired by this PR: 2 sites** — `schema/interface.clj:84` and `dag-primitives/result.clj:48`. Both tagged "high-leverage" foundations in the inventory's recommended week-1 ordering.
- **Set up to be retired by follow-ons: ~38 sites** — `:handle-not-found` and `:auth-invalid` entries across `connector-jira` (~5), `connector-gitlab` (~5), `connector-github` (~6), `connector-excel` (~5), `connector-edgar` (~3), `connector-file` (~3), `connector-sarif` (~2), `connector-pipeline-output` (~1).

## Strata Affected

- `ai.miniforge.schema.interface` — new anomaly variant + delegated thrower
- `ai.miniforge.dag-primitives.interface` + `.result` — new anomaly variant + delegated thrower
- `ai.miniforge.connector.interface` + new `.validation` — extracted shared helpers

## Testing Plan

- `bb pre-commit` green: lint:clj + fmt:md + test (**2906 tests / 10675 passes / 0 failures**) + test:graalvm (6 tests / 468 assertions / 0 failures).
- All 12 new test files exercised. Each anomaly-returning fn has happy-path, failure-path, and throwing-variant compatibility tests.
- clj-kondo emits intentional deprecation warnings on the deprecated throwers; only fires inside the compat tests that exercise those paths.

## Deployment Plan

No migration. Backward-compatible.

- Callers of the existing throwing functions see no change. Same signatures, same `ex-info` shape on failure.
- New code should reach for the anomaly-returning variants. Existing call sites can migrate at their leisure (or be migrated by follow-on PRs).
- Promotion of the linter rule to `:error` for these specific files happens after the per-connector callsite migrations land.

## Notes / Design calls

1. **Naming inversion.** The brief described the convention as "`validate!` (throws) vs `validate` (anomaly)." But the existing throwers in this codebase are unsuffixed (`validate`, `unwrap`). Renaming would break every downstream caller. Kept the unsuffixed name as the deprecated thrower; introduced `validate-anomaly` / `unwrap-anomaly` for the new shape. Connector helpers are brand-new — followed the spec naming exactly there (`require-handle` anomaly, `require-handle!` thrower).
2. **dag-primitives anomaly type chosen as `:fault`.** The original err result is a programmer/system fault by convention (caller should have checked `ok?`). `:fault` is more accurate than `:invalid-input`. Original `:code` keyword preserved in `:anomaly/data :code`.
3. **`validate-auth` returns `nil` on success.** Mirrors the `validate-auth!` shape where success was just "no throw." Callers can use `(when-let [a (validate-auth auth)] …)` to short-circuit on anomaly.
4. **Test runner flake observed during work** — `dag-executor.state-test/transition-task!-emits-event-test` raced with `gate.behavioral-test` (which `with-redefs [clojure.core/requiring-resolve …]`). Not introduced by this PR; pre-existing brick-isolation hazard around event-stream global state. Final pre-commit run is green; flagging as a separate hygiene issue worth a follow-on fix.

## Related Issues/PRs

- Sibling H-series PR `feat/response-chain-component`
- Sibling linter PR `feat/exceptions-as-data-linter`
- Built on the merged `feat/anomaly-component` and `005 exceptions-as-data` standards rule
- Inventory: `work/exception-cleanup-inventory.md` (merged)

## Checklist

- [x] Three foundation scopes covered (schema, dag-primitives, connector-core extraction)
- [x] Existing tests still pass; throwing variants behave identically for existing callers
- [x] New anomaly-returning variants have decomposed tests (12 new test files total)
- [x] Backward-compatible — no caller migration required for this PR
- [x] Apache 2 headers on every modified/new file
- [x] `bb pre-commit` green
