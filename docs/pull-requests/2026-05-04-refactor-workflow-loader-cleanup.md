# refactor: exceptions-as-data cleanup of workflow (loader side)

## Overview

Finishes the `workflow` component's exceptions-as-data cleanup. Migrates every loader-side `:cleanup-needed` throw site (9 of 9 per `work/exception-cleanup-inventory.md`) to a single anomaly-returning API. Boundary throws live only at the call sites that need to escalate to slingshot, inlined per the kill-the-deprecation pattern from PR #777.

Pairs with the runtime-side cleanup (PR #769 → PR #777). Together they retire the workflow component's full `:cleanup-needed` row in the inventory.

## Motivation

Per the inventory, `workflow` was the second-highest-density cleanup target after `repo-dag`. The runtime side landed in two PRs (#769 introduced anomaly variants, #777 killed the deprecated throwers). This PR mirrors that final shape directly — anomaly-returning helpers as the canonical API; throws inlined at boundary call sites — and applies it to the loader / registry / schemas surface in one PR rather than two.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary and constructor
- `005 exceptions-as-data` standards rule (merged)

Builds on:

- PR #704 (foundation cleanup)
- PR #758 (repo-dag cleanup)
- PR #769 / #777 (workflow runtime cleanup + kill-the-deprecation)

## Layer

Refactor / per-component cleanup tier. Workflow loader namespaces only; runtime side is unchanged from PR #777.

## What This Adds / Changes

`components/workflow/src/ai/miniforge/workflow/loader.clj`:

- `load-from-resource` returns `nil` | workflow | `:fault` anomaly on parse failure (was: `response/throw-anomaly!` of `:anomalies/fault`)
- `validate-and-cache-workflow` returns result map | `:invalid-input` anomaly on validator failure (was: throw)
- `load-workflow` becomes the single boundary site that inlines `response/throw-anomaly!` for source faults, validation failures, and the missing-everywhere not-found case

`components/workflow/src/ai/miniforge/workflow/chain_loader.clj`:

- `try-load-chain` returns result | `:not-found` anomaly
- `load-chain` is the boundary that re-escalates as `ex-info`

`components/workflow/src/ai/miniforge/workflow/registry.clj`:

- `load-workflow-from-resource` returns workflow | `:fault` anomaly
- `discover-workflows-from-resources` filters anomalies out of its sequence so one bad EDN no longer aborts discovery
- `try-workflow-characteristics` returns characteristics | `:invalid-input` anomaly
- `workflow-characteristics` is the boundary that re-escalates under `:anomalies.workflow/invalid-config`
- `missing-workflow-id-anomaly` and `validate-workflow-registration` return anomalies on failure
- `register-workflow!` inlines two boundary throws (`:anomalies/incorrect`, `:anomalies.workflow/invalid-config`)

`components/workflow/src/ai/miniforge/workflow/schemas.clj`:

- `validate-checkpoint-data` returns value | `:invalid-input` anomaly
- `validate-checkpoint-data!` re-escalates via `ex-info`

`components/workflow/test/ai/miniforge/workflow/anomaly/`:

- Decomposed coverage, one file per scope: `load-from-resource-test`, `validate-and-cache-workflow-test`, `try-load-chain-test`, `load-workflow-from-resource-test`, `workflow-characteristics-test`, `register-workflow-test`, `validate-checkpoint-data-test`. Each covers anomaly happy / anomaly failure / boundary-escalation through the throwing entry point.

## Per-site classification

| File | Function | `:anomaly/type` | Why |
|---|---|---|---|
| `loader.clj` | `load-from-resource` | `:fault` | EDN parse / I/O failure on a resource — system-level, not caller input. |
| `loader.clj` | `validate-and-cache-workflow` | `:invalid-input` | Workflow EDN failed schema validation — caller-supplied shape rejected. |
| `loader.clj` | `load-workflow` (not-found path) | `:not-found` | All sources tried; nothing returned. Inlined boundary throw. |
| `chain_loader.clj` | `try-load-chain` | `:not-found` | Chain ID absent from every source. |
| `registry.clj` | `load-workflow-from-resource` | `:fault` | Discovery-time read failure on a single resource; non-blocking via filter. |
| `registry.clj` | `try-workflow-characteristics` | `:invalid-input` | Workflow body failed characteristics extraction (config shape wrong). |
| `registry.clj` | `missing-workflow-id-anomaly` | `:invalid-input` | Caller registration missing `:workflow/id`. |
| `registry.clj` | `validate-workflow-registration` | `:invalid-input` | Workflow body fails registration schema. |
| `schemas.clj` | `validate-checkpoint-data` | `:invalid-input` | Checkpoint shape rejected by malli. |

## API surface (after this PR)

```clojure
;; Canonical, anomaly-returning. Component-internal.
ai.miniforge.workflow.loader/load-from-resource             ; returns nil | workflow | :fault anomaly
ai.miniforge.workflow.loader/validate-and-cache-workflow    ; returns result | :invalid-input anomaly
ai.miniforge.workflow.chain-loader/try-load-chain           ; returns result | :not-found anomaly
ai.miniforge.workflow.registry/load-workflow-from-resource  ; returns workflow | :fault anomaly
ai.miniforge.workflow.registry/try-workflow-characteristics ; returns characteristics | :invalid-input anomaly
ai.miniforge.workflow.registry/missing-workflow-id-anomaly  ; returns nil | :invalid-input anomaly
ai.miniforge.workflow.registry/validate-workflow-registration ; returns nil | :invalid-input anomaly
ai.miniforge.workflow.schemas/validate-checkpoint-data      ; returns value | :invalid-input anomaly

;; Boundary entry points — escalate anomalies to slingshot ex-info.
ai.miniforge.workflow.loader/load-workflow                  ; throws on fault / invalid / not-found
ai.miniforge.workflow.chain-loader/load-chain               ; throws on :not-found
ai.miniforge.workflow.registry/workflow-characteristics     ; throws on :anomalies.workflow/invalid-config
ai.miniforge.workflow.registry/register-workflow!           ; throws :anomalies/incorrect or :anomalies.workflow/invalid-config
ai.miniforge.workflow.schemas/validate-checkpoint-data!     ; throws ex-info
```

No deprecated throwers retained. The boundary helpers preserve the same slingshot `:anomaly/category` shapes external callers above the component depend on (CLI / MCP / orchestrator).

## Strata Affected

- `ai.miniforge.workflow.loader` — anomaly-returning `load-from-resource` + `validate-and-cache-workflow`; boundary `load-workflow`
- `ai.miniforge.workflow.chain-loader` — anomaly-returning `try-load-chain`; boundary `load-chain`
- `ai.miniforge.workflow.registry` — anomaly-returning helpers; boundary `workflow-characteristics` and `register-workflow!`
- `ai.miniforge.workflow.schemas` — anomaly-returning `validate-checkpoint-data`; boundary `validate-checkpoint-data!`

## Inventory delta

- **Loader side (this PR): 9 of 9 `:cleanup-needed` sites retired.**
- **Workflow component total** (runtime + loader): 12 of 12 retired.

## Testing Plan

- `bb test`: **5046 tests / 23007 passes / 0 failures / 0 errors**.
- `bb lint:clj`: 0 errors, 0 warnings.
- Decomposed coverage per scope under `test/.../anomaly/`.

## Deployment Plan

No migration required for external callers. The boundary entry points (`load-workflow`, `load-chain`, `workflow-characteristics`, `register-workflow!`, `validate-checkpoint-data!`) preserve the same thrown ex-info shapes that legacy `try+` callers above the workflow component expect.

New code can opt into the anomaly-returning helpers directly (e.g. `try-load-chain` for callers that want to branch on `:not-found` as data without try+ machinery). Discovery paths (`discover-workflows-from-resources`) now degrade gracefully: a single broken resource no longer aborts the whole sequence.

## Notes / Surprises

- **Discovery resilience improvement.** `discover-workflows-from-resources` previously aborted the entire sequence the moment any single resource failed to read. With `load-workflow-from-resource` now anomaly-returning, the discovery sequence filters anomalies out — one bad EDN no longer hides every other workflow. Behavior change is intentional and consistent with how a registry should behave; flagged here for visibility.
- **`some` / boolean schema gap.** While migrating, the agent observed a place where a `(some pred coll)` result is fed into a malli schema that expected boolean. This is a pre-existing pattern, not introduced by this PR; flagged as a follow-up hygiene item.
- **Commit-budget gate.** Same friction as PR #758 / #769 / #777. Override path used per established precedent.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent for the workflow component)
- Built on PR #704 (foundation cleanup)
- Built on PR #758 (repo-dag cleanup)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)

## Checklist

- [x] All 9 loader-side `:cleanup-needed` workflow sites retired
- [x] Single API per site (no deprecate-and-coexist)
- [x] Boundary throws inlined at the call sites that escalate
- [x] External caller contracts preserved — same slingshot `:anomaly/category` shapes
- [x] Decomposed test files (seven) — anomaly happy / anomaly failure / boundary-escalation per scope
- [x] No new throws in anomaly-returning code paths
- [x] `bb test` green: 5046 / 23007 / 0
- [x] `bb lint:clj` clean
- [x] Apache 2 license headers preserved
