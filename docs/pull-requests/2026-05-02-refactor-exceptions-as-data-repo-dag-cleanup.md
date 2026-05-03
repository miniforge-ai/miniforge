# refactor: exceptions-as-data cleanup of repo-dag

## Overview

Migrates every `:cleanup-needed` throw site in `repo-dag` (12 of 12 per the merged inventory) to anomaly-returning
variants alongside the existing throwers. The throwing public API is preserved for backward compatibility — every
existing caller sees identical `ex-info` shapes — but new callers can opt into the data-first variants.

`repo-dag` was the highest-density single-file cleanup target per `work/exception-cleanup-inventory.md` (recommended
ordering item #1 after the foundation tier).

## Motivation

The merged foundation cleanup (PR #704) extracted `schema/validate-anomaly` and `dag-primitives/unwrap-anomaly` as the
canonical anomaly-returning patterns. The merged connector callsite migration (PR #734) demonstrated the per-component
cleanup shape. This PR continues the workstream into the highest-leverage non-connector component.

Per the inventory, all 12 sites in `repo-dag` cluster around DAG existence checks, edge-relation lookups, and
structural-invariant guards — mechanical to convert with high downstream payoff (every caller of repo-dag's read-shaped
methods can now compose with response-chains and inference-evidence cleanly).

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary and constructor
- `005 exceptions-as-data` standards rule (merged) — defines the discipline this PR applies

## Layer

Refactor / per-component cleanup tier. `repo-dag` only — no other component touched. Per-callsite migration of repo-dag
consumers (web-dashboard, bases/cli/monitoring, fleet-onboarding integration tests) is a separate workstream.

## What This Adds / Changes

`components/repo-dag/deps.edn`:

- Added `:local/root` dep on `ai.miniforge/anomaly`.

`components/repo-dag/src/.../core.clj`:

- Added 9 anomaly-returning protocol methods + `validate-schema-anomaly` + `make-repo-node-anomaly` +
  `make-repo-edge-anomaly` + private `*-impl` and `build-*` helpers
- Throwing methods now delegate to the anomaly variants and translate any returned anomaly back into the original
  `ex-info` shape for backward-compat callers

`components/repo-dag/src/.../interface.clj`:

- Exported 9 new anomaly-returning fns alongside the existing throwers
- The 9 deprecated throwers carry `{:deprecated "exceptions-as-data — prefer *-anomaly"}` markers and updated docstrings
  pointing at the new variants

`components/repo-dag/test/.../anomaly/`:

- Six new decomposed test files: `validate_schema_test.clj`, `add_repo_test.clj`, `remove_repo_test.clj`,
  `add_edge_test.clj`, `remove_edge_test.clj`, `queries_test.clj`. Each file pins happy-path / failure-path /
  throwing-variant compatibility for its scope.

## Per-site classification

| Original throw site | `:anomaly/type` | Why |
|---|---|---|
| Schema validation failures | `:invalid-input` | Input shape rejected by malli |
| "Repo already exists" / "Edge already exists" | `:conflict` | Operation conflicts with existing state |
| "Adding edge would create cycle" | `:conflict` | Operation conflicts with structural invariant |
| "DAG not found" / "From repo not found" / "To repo not found" | `:not-found` | Referenced entity missing |
| "Self-loop not allowed" | `:invalid-input` | Caller-supplied data shape rejected |

## New API surface

Added to `ai.miniforge.repo-dag.interface`:

```clojure
add-repo-anomaly
remove-repo-anomaly
add-edge-anomaly
remove-edge-anomaly
compute-topo-order-anomaly
affected-repos-anomaly
upstream-repos-anomaly
merge-order-anomaly
validate-dag-anomaly
```

Added to `ai.miniforge.repo-dag.core` (internal; accessible to tests via `#'ns/private`):

```clojure
validate-schema-anomaly
validate-schema  ; now delegates to the anomaly variant
```

## Inventory delta

**12 of 12 `:cleanup-needed` repo-dag sites retired.** Every throw in `core.clj` after this PR lives only inside the
  deprecated thrower wrappers (`validate-schema`, `make-repo-node`, `make-repo-edge`, and the protocol-layer throwing
  methods that delegate to their anomaly siblings and re-wrap). The anomaly-returning code path through
  `add-repo-anomaly` / `add-edge-anomaly` no longer reaches a throw — `make-repo-node-anomaly` /
  `make-repo-edge-anomaly` short-circuit on schema validation failure with `:invalid-input` instead of escaping as
  `ExceptionInfo` (the original draft of this PR missed that — `add-*-anomaly` called the throwing constructors and
  silently violated the contract; tests in `add_repo_test` / `add_edge_test` pin the schema-invalid path now).

## Strata Affected

- `ai.miniforge.repo-dag.core` — added anomaly methods + private impl helpers; throwing methods now delegate
- `ai.miniforge.repo-dag.interface` — anomaly-method exports added; deprecation markers on throwers

## Testing Plan

- **`bb pre-commit` green:**
  - `lint:clj` — clean
  - `fmt:md` — clean
  - `test` — **4848 tests / 22386 assertions / 0 failures / 0 errors**
  - `test:graalvm` — 6 tests / 487 assertions / 0 failures
- Component-scoped: 65 tests / 188 assertions / 0 failures across the existing 5 test files + the 6 new anomaly test
  files

## Deployment Plan

No migration required. Backward-compatible.

- Existing throwing-API callers see no change. Same signatures, same `ex-info` shape on failure.
- New code opts into the anomaly-returning variants (`*-anomaly` suffix).
- Per-callsite migration of `repo-dag` consumers (web-dashboard / bases-cli-monitoring / fleet-onboarding integration
  tests) is queued as follow-on work.

## Notes / Surprises

- **`compute-topo-order` was previously asymmetric.** It already returned an anomaly-shaped `{:success bool ...}` for
  the cycle case but threw for the missing-DAG case. The new `compute-topo-order-anomaly` carries the same
  success/failure map on hit and a canonical `:not-found` anomaly on miss, ending the two-error-system mix on this method.
- **Commit-budget gate friction.** The merged commit-budget gate (PR #752) treated this many-site refactor as
  over-budget by default (1099 lines, mostly tests + delegated wrappers). Override path documented and rationale
  recorded in the commit body — the protocol body must change atomically because the throwing methods delegate to the
  anomaly methods, so splitting would leave intermediate commits uncompilable. Worth flagging as a likely repeat
  friction point for the rest of the exceptions-cleanup workstream; an alternate path is to authorize larger budgets
  explicitly for `:exceptions-as-data` cleanup PRs.
- **External callers are sparse.** `web-dashboard/state/trains.clj` (via `safe-call`), `bases/cli/.../monitoring.clj`
  (lazy-required), and `projects/miniforge/test/.../fleet_onboarding_integration_test.clj` — all exercise read-shaped
  paths and are unbroken by the deprecation. Per-callsite migration is queued as follow-on.

## Related Issues/PRs

- Built on PR miniforge#704 (foundation cleanup — `schema/validate-anomaly`, `dag-primitives/unwrap-anomaly`,
  `connector/require-handle`)
- Built on PR miniforge#734 (per-connector callsite migration)
- Tracked in PR miniforge#691 (`work/exception-cleanup-inventory.md`)
- Sibling PR: thesium-workflows#34 (submodule bump pulling in boundary)

## Checklist

- [x] All 12 `:cleanup-needed` repo-dag sites retired
- [x] Existing throwing API preserved (delegated, behavior identical)
- [x] Anomaly-returning variants for every public protocol method
- [x] Deprecation markers on throwers + docstring pointers
- [x] Decomposed test files (six new) — happy / failure / compat per scope
- [x] No new throws in anomaly-returning code paths
- [x] `bb pre-commit` green
- [x] Apache 2 license headers preserved
