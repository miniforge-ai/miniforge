# refactor: exceptions-as-data cleanup of operator

## Overview

Migrates the 5 `:cleanup-needed` throw sites in `operator` (all clustered around the intervention validation cascade in `intervention.clj`) to a single anomaly-returning API. Each step in the cascade (unknown type, target-type unresolvable, unknown target-type, missing target-id, missing requester) now returns its own `:invalid-input` anomaly with structured `:anomaly/data`. A boundary throwing variant `create-intervention!` lives alongside for the in-component sites that need to escalate to `ex-info`.

## Motivation

Per `work/exception-cleanup-inventory.md`, `operator` was flagged with the note "5 of 6 hits cleanup-needed (intervention validation cascade in one function)". Mechanical cleanup with the kill-the-deprecation pattern from PR #777. The cascade structure made this a textbook case — five separate validation steps, each its own anomaly return, short-circuiting in order.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary and constructor

## Layer

Refactor / per-component cleanup tier. Single component (`operator`); single file primarily (`intervention.clj`) plus interface re-exports.

## What This Adds / Changes

`components/operator/deps.edn`:

- Adds `:local/root` dep on `ai.miniforge/anomaly`.

`components/operator/src/ai/miniforge/operator/intervention.clj`:

- Introduces five private validation helpers, each returning `nil | :invalid-input anomaly`:
  - `validate-intervention-type` — unknown type
  - `validate-target-type-resolvable` — neither caller value nor vocabulary default
  - `validate-target-type-known` — resolved target type not in recognized set
  - `validate-target-id` — missing `:intervention/target-id`
  - `validate-requester` — missing `:requested-by` or `:request-source`
- `create-intervention` is now the canonical anomaly-returning entry point. Threads each validator in cascade order; the first non-nil anomaly short-circuits and is returned to the caller.
- `create-intervention!` is the boundary helper that calls `create-intervention` and re-throws via slingshot (`ex-info`) when the result is an anomaly. Used for in-component sites that previously threw at the cascade boundary; preserves the legacy `ex-info` shape for any external `try+` callers.
- `build-intervention` is the assembly path post-validation. Pure construction.

`components/operator/src/ai/miniforge/operator/interface.clj`:

- Re-exports both `create-intervention` (canonical, anomaly-returning) and `create-intervention!` (boundary, throwing). Docstrings cite which to prefer.

`components/operator/test/.../intervention_test.clj`:

- Existing test that asserted thrown ex-info on bad input migrated to assert the anomaly-returning shape (one assertion swap; identical ex-data shape now lives in `:anomaly/data`).

`components/operator/test/.../anomaly/create_intervention_test.clj` (new):

- Decomposed test coverage. One file with deftests covering each cascade step:
  - happy path (valid request returns the constructed intervention)
  - unknown intervention type (rejects with the right `:anomaly/data`)
  - target-type unresolvable (uses `with-redefs` on the impl-level `valid-type?` to admit a synthetic type with no default-target-type mapping; pins the `:target-type-required` message)
  - unknown target-type (caller-supplied target type not in recognized set)
  - missing target-id
  - missing requester
  - boundary throw via `create-intervention!`

## Per-site classification

| Site | Anomaly type | Boundary? |
|------|-------------:|-----------|
| `validate-intervention-type` (unknown type) | `:invalid-input` | No — returned through cascade |
| `validate-target-type-resolvable` (no default) | `:invalid-input` | No |
| `validate-target-type-known` (unknown target type) | `:invalid-input` | No |
| `validate-target-id` (missing target-id) | `:invalid-input` | No |
| `validate-requester` (missing requester / source) | `:invalid-input` | No |
| `create-intervention!` (escalation) | — | Yes — slingshot `ex-info` for legacy callers |

All five validation steps remain separate (per the brief: "do not collapse them into one composite check unless the combined message is genuinely clearer"). Cascade order matches the pre-cleanup order; the same input still gets the same first-rejection message it would have produced before.

## Strata Affected

- `ai.miniforge.operator.intervention` — anomaly-returning validators + canonical fn + boundary helper
- `ai.miniforge.operator.interface` — adds `create-intervention!` re-export
- `ai.miniforge.operator.intervention-test` — one assertion migrated to the anomaly shape

## Testing Plan

- `bb test`: **5103 tests / 23250 passes / 0 failures / 0 errors**.
- Decomposed coverage under `components/operator/test/.../anomaly/create_intervention_test.clj`.
- One test required `with-redefs` on the impl-level `intervention/valid-type?` to exercise the "no default target type" path; the original draft patched the interface re-export `op/valid-intervention-type?`, which the validation cascade does not call.

## Deployment Plan

No migration. The `create-intervention` name is preserved (now anomaly-returning); callers that previously expected a thrown `ex-info` now get an anomaly map back. Code that wants the old throwing contract calls `create-intervention!` instead.

In-component callers in `operator` itself have been updated to consume the new shape. External callers above the operator boundary continue to see the same slingshot `:anomaly/category` shape via `create-intervention!`.

## Notes

- **Cascade ordering preserved.** First-rejection-wins is unchanged from the pre-cleanup behavior; same input still produces the same first error.
- **License headers** preserved on every file.
- **Apache 2 by default** — operator is OSS.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation pattern precedent for the workflow component)
- Built on PR #704 (foundation cleanup)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)
- Companion to Wave 7 cleanup PRs — spec-parser, agent component, task

## Checklist

- [x] All 5 `:cleanup-needed` operator sites retired
- [x] Single API per validation step (no deprecate-and-coexist)
- [x] Boundary throw inlined via `create-intervention!`
- [x] External caller contracts preserved — same slingshot ex-info shape via the boundary fn
- [x] Decomposed test file covers each cascade step
- [x] No new throws in anomaly-returning code paths
- [x] `bb test` green: 5103 / 23250 / 0
- [x] Apache 2 license headers preserved
