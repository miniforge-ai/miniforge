# refactor: kill workflow runtime deprecated throwers (single API)

## Overview

Follow-on cleanup to PR #769 (which migrated `workflow` runtime sites to the deprecate-and-coexist anomaly-returning pattern). This PR kills the deprecated throwers entirely so each site has a single canonical API. Three throwers retired:

- `workflow.state/transition-status` (deprecated thrower)
- `workflow.runner-environment/assert-executor-for-mode!` (deprecated thrower)
- `workflow.runner/build-initial-context` (deprecated private thrower)

The boundary throws that legitimately need to escalate to a slingshot ex-info shape (preserving the contract for `try+` callers above the workflow component) are inlined at their single call sites.

## Motivation

PR #769 landed with the same deprecate-and-coexist pattern that earlier exceptions-as-data PRs (#704 foundation, #758 repo-dag) established. Reviewer feedback pointed out that pattern is over-cautious for a 17-star repo with one primary maintainer and a handful of internal callers per site:

- The deprecated throwers were component-internal — zero external callers across the repo.
- The pattern paid an ongoing surface-area + deprecation-warnings tax.
- The savings (smaller individual PRs, easy revert) didn't justify it at this scale.

This PR drops the coexistence and lands a single API per site. Future component-scope cleanups will follow this pattern. Foundation-tier APIs with >30 callers (`schema/validate`, `dag-primitives/unwrap`) keep coexistence — the call-site count makes hard cuts genuinely impractical there.

## Base Branch

`main` (post-#769)

## Depends On

- PR #769 — `refactor/exceptions-as-data-workflow-runtime-cleanup` (merged) — introduced the anomaly variants this PR keeps as canonical.

## Layer

Refactor / kill-the-deprecation cleanup. Three workflow-runtime files; component-internal — no `interface.clj` exports affected.

## What This Adds / Changes

`components/workflow/src/ai/miniforge/workflow/state.clj`:

- Deleted the deprecated `transition-status` thrower.
- Renamed `transition-status-anomaly` → `transition-status` (canonical name now belongs to the canonical fn).
- Added private `transition-status!` boundary helper. Used by `mark-completed`, `mark-failed`, `transition-to-phase` — these represent workflow-definition invariants; an FSM rejection at one of them is a programmer error, so escalating to slingshot is appropriate. Preserves the legacy `:anomalies.workflow/invalid-transition` ex-info shape.

`components/workflow/src/ai/miniforge/workflow/runner_environment.clj`:

- Deleted the deprecated `assert-executor-for-mode!` thrower.
- Renamed `check-executor-for-mode-anomaly` → `check-executor-for-mode`.
- Inlined the boundary throw at the single call site in `acquire-execution-environment!`. Preserves the legacy `:anomalies.executor/unavailable` ex-info shape for legacy `try+` callers above this layer.

`components/workflow/src/ai/miniforge/workflow/runner.clj`:

- Deleted the deprecated private `build-initial-context` thrower.
- Renamed `build-initial-context-anomaly` → `build-initial-context`.
- Inlined the boundary throw at the single call site in `run-pipeline`'s let bindings. Preserves the `:anomalies.workflow/no-capsule-executor` ex-info shape that external callers (CLI / MCP / orchestrator) expect on misconfiguration.

`components/workflow/test/.../anomaly/`:

- `transition_status_test.clj` — references `transition-status` (not `*-anomaly`); deprecated-thrower compat tests replaced with `mark-completed` / `mark-failed` integration tests that exercise the private `transition-status!` boundary helper.
- `check_executor_for_mode_test.clj` — references `check-executor-for-mode`; `assert-executor-for-mode!` tests replaced with `acquire-execution-environment!` integration tests (with stubbed `dag-executor-fns`) that exercise the inlined boundary throw.
- `build_initial_context_test.clj` — references `build-initial-context`; deprecated-thrower compat tests removed. Run-pipeline boundary integration test omitted with a documented rationale: `acquire-environment` runs first along that path with its own governed-mode throw, so the integration test would test the wrong escalation site. Coverage for the build-initial-context boundary specifically is delegated to the runner's existing pipeline tests.

## API surface (after this PR)

```clojure
;; Canonical, anomaly-returning. Component-internal.
ai.miniforge.workflow.state/transition-status                         ; was *-anomaly
ai.miniforge.workflow.runner-environment/check-executor-for-mode      ; was *-anomaly
ai.miniforge.workflow.runner/build-initial-context                    ; was *-anomaly

;; Private boundary helper for in-component invariants.
ai.miniforge.workflow.state/transition-status!                        ; private
```

The deprecated throwers (`transition-status` thrower, `assert-executor-for-mode!`, `build-initial-context` thrower wrapper) are **gone**. Boundary escalation to slingshot ex-info now lives inlined at the call sites that actually need to throw.

## External caller contracts

Unchanged. External callers above the workflow component see the same slingshot `:anomaly/category` shapes as before:

- `acquire-execution-environment!` still throws `:anomalies.executor/unavailable` for governed-mode-without-capsule.
- `run-pipeline` still throws `:anomalies.workflow/no-capsule-executor` when build-initial-context returns the no-capsule anomaly.
- `mark-completed` / `mark-failed` / `transition-to-phase` still throw `:anomalies.workflow/invalid-transition` on FSM rejection (now via private `transition-status!`).

## Strata Affected

- `ai.miniforge.workflow.runner` — `build-initial-context` is now anomaly-returning; boundary throw inlined in `run-pipeline`
- `ai.miniforge.workflow.runner-environment` — `check-executor-for-mode` is anomaly-returning; boundary throw inlined in `acquire-execution-environment!`
- `ai.miniforge.workflow.state` — `transition-status` is anomaly-returning; private `transition-status!` boundary helper used by `mark-completed` / `mark-failed` / `transition-to-phase`

## Testing Plan

- `bb test` — **5003 tests / 22890 passes / 0 failures / 0 errors** (post-cleanup; deprecated-thrower compat tests removed; new boundary integration tests added).
- No deprecation warnings emitted (no deprecated throwers remain).

## Deployment Plan

No migration required for external callers — these helpers are component-internal (no `interface.clj` exports). External callers continue to see the same slingshot `:anomaly/category` shapes when escalation happens at the component boundaries.

## Notes / Surprises

- **Policy update.** This PR codifies the kill-the-deprecation pattern for component-scope cleanups. Foundation-tier APIs (>~30 callers; `schema/validate`, `dag-primitives/unwrap`) keep coexistence because hard cuts are genuinely impractical there. Documented in the commit body and reflected in the wave-6 closing summary.
- **Run-pipeline boundary integration test omitted.** The build-initial-context throw escalation through `run-pipeline` is hard to exercise because `acquire-environment` runs first and has its own throw on the same governed-mode-without-capsule scenario. The boundary escalation pattern is tested via `acquire-execution-environment!` in `check_executor_for_mode_test.clj`. End-to-end coverage of the build-initial-context boundary is the runner's existing pipeline tests.
- **`:anomalies.workflow/no-capsule-executor` is still not in `response/anomaly/workflow-anomalies` taxonomy.** Pre-existing latent quirk — `throw-anomaly!` doesn't validate against the taxonomy. Preserved at the new inlined boundary throw; flagged for the loader-side cleanup or a separate hygiene pass.

## Related Issues/PRs

- Built on PR #769 (workflow runtime cleanup with deprecate-and-coexist) — which itself built on:
  - PR #704 (foundation cleanup)
  - PR #758 (repo-dag cleanup)
- Loader-side `workflow` cleanup still deferred (~9 sites; separate PR)

## Checklist

- [x] All 3 workflow-runtime deprecated throwers removed
- [x] Single API per site (no deprecate-and-coexist)
- [x] Boundary throws inlined at the call sites that escalate
- [x] External caller contracts preserved — same slingshot `:anomaly/category` shapes
- [x] Tests updated — anomaly happy / anomaly failure / boundary-escalation per scope
- [x] No new throws in anomaly-returning code paths
- [x] `bb test` green: 5003 / 22890 / 0
- [x] Apache 2 license headers preserved
