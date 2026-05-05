# refactor: exceptions-as-data cleanup of workflow (runtime side)

## Overview

Migrates the runtime-side `:cleanup-needed` throw sites in the `workflow` component to a single anomaly-returning API per site. Three throwers killed (no deprecate-and-coexist): `runner/build-initial-context`, `runner-environment/assert-executor-for-mode!`, and `state/transition-status`. The boundary throws that need to escalate to the slingshot ex-info shape (for legacy `try+` callers above this layer) are inlined at their single call sites — so the throw still happens, but at the right architectural seam, not in a deprecated parallel API.

Loader/registry-side sites (~9) are deferred to a follow-on PR per the inventory's recommended split.

## Motivation

Per `work/exception-cleanup-inventory.md`, `workflow` is the second-highest-density cleanup target after `repo-dag` (merged in PR #758). The inventory explicitly recommended a 2-PR split (runtime + loader/registry) due to risk surface — this PR is the runtime side.

Runtime cleanup has the highest downstream payoff: every workflow execution path now composes cleanly with response-chains and inference-evidence rather than relying on slingshot exceptions for non-control-flow failures.

This PR also drops the deprecate-and-coexist pattern that earlier exceptions-as-data PRs established (#704 foundation, #758 repo-dag, #769's first iteration). For component-internal helpers like these — zero external callers across the repo — the pattern paid an ongoing tax in surface area, deprecation warnings, and reviewer overhead, and the savings (smaller individual PRs, cheap revert) didn't justify it. Single API per site; throws inlined at the boundary where they actually need to happen.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary and constructor
- `005 exceptions-as-data` standards rule (merged)

## Layer

Refactor / per-component cleanup tier. `workflow` runtime namespaces only — no loader/registry change in this PR.

## What This Adds / Changes

`components/workflow/deps.edn`:

- Added `:local/root` dep on `ai.miniforge/anomaly`.

Source files modified (3):

- `components/workflow/src/ai/miniforge/workflow/runner.clj` — `build-initial-context` is now anomaly-returning (canonical name; the deprecated thrower is gone). `governed-capsule-missing-anomaly` is the private pre-flight check. The boundary escalation throw is inlined in `run-pipeline`'s let bindings — `(when (anomaly? ctx-or-anomaly) (response/throw-anomaly! :anomalies.workflow/no-capsule-executor …))` — so the contract for external callers (CLI / MCP / orchestrator) is unchanged.
- `components/workflow/src/ai/miniforge/workflow/runner_environment.clj` — `check-executor-for-mode` is anomaly-returning. The deprecated `assert-executor-for-mode!` is gone. The boundary escalation throw is inlined in `acquire-execution-environment!` at the single call site, preserving the slingshot `:anomalies.executor/unavailable` shape.
- `components/workflow/src/ai/miniforge/workflow/state.clj` — `transition-status` is the canonical anomaly-returning fn. A private `transition-status!` boundary helper escalates an anomaly to a slingshot throw — used by `mark-completed`, `mark-failed`, and `transition-to-phase`, which represent workflow-definition invariants where an FSM rejection is a programmer error.

New decomposed test files (3):

- `components/workflow/test/.../anomaly/build_initial_context_test.clj` — happy + anomaly paths for `build-initial-context`; documents why the run-pipeline boundary integration test is left out (acquire-environment fails first along that path; coverage already exists in `check_executor_for_mode_test.clj`).
- `components/workflow/test/.../anomaly/check_executor_for_mode_test.clj` — happy + anomaly paths for `check-executor-for-mode`; boundary throw exercised end-to-end through `acquire-execution-environment!` with stubbed registry fns.
- `components/workflow/test/.../anomaly/transition_status_test.clj` — happy + anomaly paths for `transition-status`; boundary throw exercised through `mark-completed` / `mark-failed` (which use the private `transition-status!`).

## Cleavage line

The inventory's 9 cleanup-needed sites for `workflow` are loader-side; the runtime sites came from the broader inventory recount. Final split:

**Runtime side (this PR, 3 sites retired):**
- `runner.clj` — `build-initial-context`
- `runner_environment.clj` — `check-executor-for-mode!`
- `state.clj` — `transition-status`

**Loader side (deferred, 9 sites):**
- `loader.clj` (×3)
- `chain_loader.clj` (×1)
- `registry.clj` (×4)
- `schemas.clj` (×1)

Other runtime-area files (`runner_cleanup.clj`, `monitoring.clj`, `observe_phase.clj`, `agent_factory.clj`, `supervision.clj`, `dag_orchestrator.clj`) had no `:cleanup-needed` sites — only `:fatal-only` (boot-time) and `:boundary` (re-throws / event payloads). No changes required in those files.

## Per-site classification

| File | Function | `:anomaly/type` | Why |
|---|---|---|---|
| `state.clj` | `transition-status` | `:invalid-input` | FSM rejection is validation-shaped — the caller passed an event the current state cannot accept. `:conflict` was tempting, but the FSM treats this as malformed input, not a state-vs-state collision. |
| `runner_environment.clj` | `assert-executor-for-mode!` | `:unavailable` | Governed mode requires capsule; absence is downstream/system unavailability (no capsule executor present). Mirrors the existing `:anomalies.executor/unavailable` slingshot mapping. |
| `runner.clj` | `build-initial-context` | `:invalid-input` | Caller supplied `:execution-mode :governed` without the matching `:executor` + `:environment-id` — caller-side input mismatch. Pre-flight guard runs before any environment lookup. |

## Slingshot stop-anomaly in `runner.clj` — kept as control-flow

The inventory flagged the dashboard-stop throw inside `runner.clj`'s `check-stopped!` helper (`response/throw-anomaly!` to `:anomalies.dashboard/stop`) as `:ambiguous`. Verified by reading the matching catch in the same file's pipeline-loop `try+`:

```clojure
(catch [:anomaly/category :anomalies.dashboard/stop] {:keys [anomaly/message]} ...)
```

This is true cooperative cancellation control flow — the inner `try+` selectively unwinds when the dashboard issues a stop, then transitions the workflow to `:failed`. Rewriting it as a return value would require threading a sentinel through every layer between `check-stopped!` and the outer pipeline loop. Kept as-is; rationale documented in the commit body.

## API surface

```clojure
;; Canonical, anomaly-returning. Component-internal — not exported in the
;; workflow component's interface namespace.
ai.miniforge.workflow.state/transition-status                  ; was *-anomaly; renamed
ai.miniforge.workflow.runner-environment/check-executor-for-mode  ; was *-anomaly; renamed
ai.miniforge.workflow.runner/build-initial-context             ; was *-anomaly; renamed

;; Private boundary helper for in-component invariants. Used by mark-completed,
;; mark-failed, and transition-to-phase.
ai.miniforge.workflow.state/transition-status!                 ; private
```

The deprecated throwers (`transition-status` thrower, `assert-executor-for-mode!`, `build-initial-context` thrower wrapper) are **gone**. Boundary escalation to slingshot ex-info now lives inlined at the call sites that actually need to throw (`acquire-execution-environment!`, `run-pipeline`, the in-component `*-helpers` via `transition-status!`). External callers above the component see the same `:anomaly/category` shapes as before.

## Inventory delta

- **Runtime side:** 3 of 3 `:cleanup-needed` sites retired in this PR (plus the 1 ambiguous slingshot resolved as keep-as-is).
- **Loader side:** 9 sites deferred to follow-on PR (`loader.clj` ×3, `chain_loader.clj` ×1, `registry.clj` ×4, `schemas.clj` ×1).

## Strata Affected

- `ai.miniforge.workflow.runner` — `build-initial-context` is now anomaly-returning; boundary throw inlined in `run-pipeline`
- `ai.miniforge.workflow.runner-environment` — `check-executor-for-mode` is anomaly-returning; boundary throw inlined in `acquire-execution-environment!`
- `ai.miniforge.workflow.state` — `transition-status` is anomaly-returning; private `transition-status!` boundary helper used by `mark-completed` / `mark-failed` / `transition-to-phase`

## Testing Plan

- **`bb pre-commit` green:**
  - `lint:clj` — clean
  - `fmt:md` — clean
  - `test` — **4928 tests / 22634 passes / 0 failures / 0 errors**
  - `test:graalvm` — 6 tests / 487 assertions / 0 failures
- No deprecation warnings emitted (no deprecated throwers remain).
- **Commit-budget overridden** (rationale recorded in commit body: kill-the-deprecation cleanup is a coherent atomic change). Same precedent as PR #758 (repo-dag).

## Deployment Plan

No migration required for external callers — these helpers were component-internal (no `interface.clj` exports). External callers above the component continue to see the same slingshot `:anomaly/category` shapes when escalation happens at the boundaries.

- `acquire-execution-environment!` still throws `:anomalies.executor/unavailable` for governed-mode-without-capsule (now via inlined check, not a separate thrower).
- `run-pipeline` still throws `:anomalies.workflow/no-capsule-executor` when build-initial-context returns an anomaly (now via inlined check on the anomaly return).
- `mark-completed` / `mark-failed` / `transition-to-phase` still throw `:anomalies.workflow/invalid-transition` on FSM rejection (via private `transition-status!`).

## Notes / Surprises

- **Policy change recorded mid-PR.** This PR initially landed with the deprecate-and-coexist pattern (anomaly variants alongside throwers, with deprecation markers). Reviewer feedback on a 17-star repo with one primary maintainer pointed out that pattern is over-cautious: it pays an ongoing surface-area + deprecation-warning tax that a small project doesn't get value from. PR rewritten to kill the throwers; only one API per site. Future component-scope cleanups follow this pattern; foundation-tier (>30 callers) keeps coexistence.
- **`:anomalies.workflow/no-capsule-executor` is still not in `response/anomaly/workflow-anomalies` taxonomy.** Pre-existing latent quirk — `throw-anomaly!` doesn't validate against the taxonomy. Preserved at the new inlined boundary throw; flagged for the loader-side cleanup or a separate hygiene pass.
- **`runner-test` and `runner-extended-test` have 14 errors / 1 failure on `main` when run in isolation against the workflow component.** They require the broader phase registry loaded via the polylith development project. Confirmed by stashing changes and rerunning — not regressions caused by this PR; project-level `bb test` passes.
- **Run-pipeline boundary integration test omitted.** The build-initial-context throw escalation through `run-pipeline` is hard to exercise because `acquire-environment` runs first and has its own throw on the same governed-mode-without-capsule scenario. The boundary escalation pattern is tested via `acquire-execution-environment!` in `check_executor_for_mode_test.clj`. End-to-end coverage is the runner's existing pipeline tests.
- **Commit-budget gate friction.** Same pattern as repo-dag (PR #758): mechanical many-site refactor + decomposed tests trips the line budget. Override path documented and worked. An `:exceptions-as-data` budget profile would eliminate the recurring friction.

## Related Issues/PRs

- Built on PR #704 (foundation cleanup)
- Built on PR #734 (per-connector callsite migration)
- Built on PR #758 (repo-dag cleanup)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)
- Loader-side follow-on: deferred (separate PR)

## Checklist

- [x] All 3 runtime-side `:cleanup-needed` workflow sites retired
- [x] Single API per site (no deprecate-and-coexist)
- [x] Boundary throws inlined at the call sites that escalate (`acquire-execution-environment!`, `run-pipeline`, `transition-status!`)
- [x] External caller contracts preserved — same slingshot `:anomaly/category` shapes
- [x] Decomposed test files (three) — anomaly happy / anomaly failure / boundary-escalation per scope
- [x] Slingshot control-flow site documented and kept as-is
- [x] No new throws in anomaly-returning code paths
- [x] `bb pre-commit` green
- [x] Apache 2 license headers preserved
