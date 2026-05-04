# refactor: exceptions-as-data cleanup of workflow (runtime side)

## Overview

Migrates the runtime-side `:cleanup-needed` throw sites in the `workflow` component to anomaly-returning variants alongside the existing throwers. Three sites retired: `runner/build-initial-context`, `runner-environment/assert-executor-for-mode!`, and `state/transition-status`. Loader/registry-side sites (~9) are deferred to a follow-on PR per the inventory's recommended split.

## Motivation

Per `work/exception-cleanup-inventory.md`, `workflow` is the second-highest-density cleanup target after `repo-dag` (now merged in PR #758). The inventory explicitly recommended a 2-PR split (runtime + loader/registry) due to the risk surface — this PR is the runtime side only.

Runtime cleanup has the highest downstream payoff: every workflow execution path now composes cleanly with response-chains and inference-evidence rather than relying on slingshot exceptions for non-control-flow failures.

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

- `components/workflow/src/ai/miniforge/workflow/runner.clj` — `build-initial-context-anomaly` added; private `governed-capsule-missing-anomaly` extracted; throwing variant delegates and re-wraps via `response/throw-anomaly!`
- `components/workflow/src/ai/miniforge/workflow/runner_environment.clj` — `check-executor-for-mode-anomaly` added; throwing variant delegates
- `components/workflow/src/ai/miniforge/workflow/state.clj` — `transition-status-anomaly` added; throwing variant delegates

New decomposed test files (3):

- `components/workflow/test/.../anomaly/build_initial_context_test.clj`
- `components/workflow/test/.../anomaly/check_executor_for_mode_test.clj`
- `components/workflow/test/.../anomaly/transition_status_test.clj`

Each pins happy-path / failure-path / throwing-variant-compatibility for its scope.

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

| Site | `:anomaly/type` | Why |
|---|---|---|
| `state.clj` `transition-status` | `:invalid-input` | FSM rejection is validation-shaped — the caller passed an event the current state cannot accept. `:conflict` was tempting, but the FSM treats this as malformed input, not a state-vs-state collision. |
| `runner_environment.clj` `assert-executor-for-mode!` | `:unavailable` | Governed mode requires capsule; absence is downstream/system unavailability (no capsule executor present). Mirrors the existing `:anomalies.executor/unavailable` slingshot mapping. |
| `runner.clj` `build-initial-context` | `:invalid-input` | Caller supplied `:execution-mode :governed` without the matching `:executor` + `:environment-id` — caller-side input mismatch. Pre-flight guard runs before any environment lookup. |

## Slingshot at `runner.clj:156` — kept as control-flow

The inventory flagged `runner.clj:156` (`response/throw-anomaly!` to `:anomalies.dashboard/stop`) as `:ambiguous`. Verified by reading the catch site at `runner.clj:391`:

```clojure
(catch [:anomaly/category :anomalies.dashboard/stop] {:keys [anomaly/message]} ...)
```

This is true cooperative cancellation control flow — the inner `try+` selectively unwinds when the dashboard issues a stop, then transitions the workflow to `:failed`. Rewriting it as a return value would require threading a sentinel through every layer between `check-stopped!` and the outer pipeline loop. Kept as-is; rationale documented in the commit body.

## New API surface

Added to public interfaces:

```clojure
ai.miniforge.workflow.state/transition-status-anomaly
ai.miniforge.workflow.runner-environment/check-executor-for-mode-anomaly
ai.miniforge.workflow.runner/build-initial-context-anomaly
```

Throwing siblings retained, marked `^{:deprecated "exceptions-as-data — prefer *-anomaly"}`, and now delegate to the anomaly variants — re-wrapping the returned anomaly into the original `response/throw-anomaly!` shape so existing callers see no behavior change.

## Inventory delta

- **Runtime side:** 3 of 3 `:cleanup-needed` sites retired in this PR (plus the 1 ambiguous slingshot resolved as keep-as-is).
- **Loader side:** 9 sites deferred to follow-on PR (`loader.clj` ×3, `chain_loader.clj` ×1, `registry.clj` ×4, `schemas.clj` ×1).

## Strata Affected

- `ai.miniforge.workflow.runner` — `build-initial-context-anomaly` added; thrower delegates
- `ai.miniforge.workflow.runner-environment` — `check-executor-for-mode-anomaly` added
- `ai.miniforge.workflow.state` — `transition-status-anomaly` added; downstream `mark-completed` / `mark-failed` / `transition-to-phase` continue to call the deprecated thrower (out of scope)

## Testing Plan

- **`bb pre-commit` green:**
  - `lint:clj` — clean
  - `fmt:md` — clean
  - `test` — **4930 tests / 22631 passes / 0 failures / 0 errors**
  - `test:graalvm` — 6 tests / 487 assertions / 0 failures
- 16 expected clj-kondo deprecation warnings on the deprecated throwers; only fire from in-component callers that exercise the legacy path.
- **Commit-budget overridden** (358 reportable lines > 200 threshold). Rationale recorded in commit body: protocol-body changes must be atomic; splitting leaves intermediate commits uncompilable. Same precedent as PR #758 (repo-dag).

## Deployment Plan

No migration required. Backward-compatible.

- Existing throwing-API callers see no change. Same signatures, same `slingshot` `:anomaly/category` shape on failure.
- New code reaches for `*-anomaly` variants; existing call sites can migrate at their leisure or be migrated by follow-ons.

## Notes / Surprises

- **`:anomalies.workflow/no-capsule-executor` is not in `response/anomaly/workflow-anomalies` taxonomy.** Pre-existing latent bug — `throw-anomaly!` doesn't validate against the taxonomy. Preserved in the throwing wrapper to keep behavior identical; flagged for the loader-side cleanup or a separate hygiene pass.
- **`runner-test` and `runner-extended-test` have 14 errors / 1 failure on `main` when run in isolation against the workflow component.** They require the broader phase registry loaded via the polylith development project. Confirmed by stashing changes and rerunning — not regressions caused by this PR; project-level `bb test` passes 4930/4930.
- **Commit-budget gate friction.** Same pattern as repo-dag (PR #758): mechanical many-site refactor + delegated wrappers + decomposed tests trips the line budget. Override path documented and worked. Worth flagging again as repeating friction; an `:exceptions-as-data` budget profile would simplify.

## Related Issues/PRs

- Built on PR #704 (foundation cleanup)
- Built on PR #734 (per-connector callsite migration)
- Built on PR #758 (repo-dag cleanup)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)
- Loader-side follow-on: deferred (separate PR)

## Checklist

- [x] All 3 runtime-side `:cleanup-needed` workflow sites retired
- [x] Existing throwing API preserved (delegated, behavior identical)
- [x] Anomaly-returning variants for every migrated site
- [x] Deprecation markers on throwers + docstring pointers
- [x] Decomposed test files (three new) — happy / failure / compat per scope
- [x] Slingshot control-flow site documented and kept as-is
- [x] No new throws in anomaly-returning code paths
- [x] `bb pre-commit` green
- [x] Apache 2 license headers preserved
