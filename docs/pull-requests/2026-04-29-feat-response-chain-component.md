# feat: add response-chain component for runtime trace + data-first errors

## Overview

Adds a new `ai.miniforge.response-chain` Polylith component to miniforge OSS. Provides the canonical in-flight runtime trace primitive that miniforge-family Clojure repos consume. Threads through a logical flow as data; each participating function appends a step (operation keyword + success? + anomaly|response). The completed chain becomes the auditable record of "what happened during this request."

## Motivation

The recently-merged `ai.miniforge.anomaly` component gave us a canonical anomaly type. The response-chain is the next layer up: an accumulator that carries those anomalies — and successful step results — through composable flows. Together they form the substrate that the new `005 exceptions-as-data` standards rule prescribes.

This is the H2 component in the cross-cutting H-series being added to miniforge OSS so that thesium-workflows, miniforge-fleet, and any future ports consume one shared error-flow vocabulary rather than reinventing it per-repo. The semantic reference is ixi's `responses-web` (read-only); this is a clean reimplementation under modern conventions, not a port.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged)

## Layer

Foundations / cross-cutting primitive. New top-level component.

## What This Adds

- New component `components/response-chain/`:
  - `deps.edn` — `:local/root` dep on `ai.miniforge/anomaly` plus malli
  - `src/ai/miniforge/response_chain/contract.clj` — Malli schemas for `Step` and `Chain`
  - `src/ai/miniforge/response_chain/core.clj` — pure-data accumulator implementation
  - `src/ai/miniforge/response_chain/interface.clj` — public API
- Nine decomposed test files under `test/ai/miniforge/response_chain/interface/`, one per behavior dimension:
  - `create_chain_test.clj`
  - `append_step_success_test.clj`
  - `append_step_anomaly_test.clj`
  - `succeeded_predicate_test.clj`
  - `last_response_test.clj`
  - `last_anomaly_test.clj`
  - `last_successful_or_test.clj`
  - `multi_step_composition_test.clj`
  - `malli_validation_test.clj`
- Workspace registration in root `deps.edn` (`:dev`, `:test`, `:conformance` aliases) + each consumer project's `deps.edn` (`projects/miniforge/`, `projects/miniforge-core/`, `projects/miniforge-tui/`). Also adds `ai.miniforge/anomaly` to those project deps where it was missing — `response-chain` requires it transitively.

## Public API

```clojure
(create-chain operation-key)              ; start a chain
(append-step chain operation-key response)         ; success step
(append-step chain operation-key anomaly response) ; anomaly step
(succeeded? step-or-chain)                ; predicate; chain succeeds iff every step succeeded
(last-response chain)                     ; most recent response
(last-anomaly chain)                      ; most recent anomaly (or nil)
(last-successful-or chain default)        ; last successful response, else default
(steps chain)                             ; vector of step maps
Chain                                     ; malli schema
Step                                      ; malli schema
```

## Data shape (validated by malli)

```clojure
Step:
  {:operation keyword?
   :succeeded? boolean?
   :anomaly [:maybe Anomaly]
   :response any?}

Chain:
  {:operation keyword?
   :succeeded? boolean?
   :response-chain [:vector Step]}
```

The chain's `:succeeded?` is the conjunction of every step's `:succeeded?`. `append-step` recomputes the invariant on every call.

## Eating our own dog food

This component IS the boundary helper for response-chains; it never throws. Malformed inputs to `append-step` produce an `:invalid-input` anomaly step rather than a thrown exception — consistent with the `005 exceptions-as-data` rule. Verified by `malli_validation_test.clj`.

## Strata Affected

- `ai.miniforge.response-chain.contract` — Malli schemas
- `ai.miniforge.response-chain.core` — implementation
- `ai.miniforge.response-chain.interface` — public API

No existing component touched. Pure additive change.

## Testing Plan

- `bb pre-commit` (lint:clj + fmt:md + test + test:graalvm): **ALL PRE-COMMIT CHECKS PASSED**
- `lint:clj` — 17 files, 0 errors / 0 warnings
- `test` — 256 namespaces, **2886 tests / 10662 passes / 0 failures / 0 errors**. The 9 response-chain test namespaces account for 42 tests / 75 assertions in this brick alone.
- `test:graalvm` — 6 tests / 473 assertions / 0 failures

The nine test files map 1:1 to the per-behavior dimensions in the brief. Each dimension is independently exercisable.

## Deployment Plan

No migration required. New additive component. Existing miniforge code unchanged.

Downstream consumption (thesium-workflows, miniforge-fleet) lands in subsequent PRs as those repos rewire flows to thread response-chains through retrieval / synthesis / agent pipelines.

## Notes / Deviations

- **Conformance alias not updated for response-chain or anomaly.** Conservatively followed the precedent set by the merged anomaly PR (which only registered in `:dev`/`:test`/root). Easy follow-up if conformance gate should cover these bricks.
- **Anomaly added to consumer project `deps.edn` files.** The original anomaly merge registered it in root only; response-chain depends on it transitively, so the project classpaths needed both. Mirrors the content-hash pattern.
- **One transient test flake observed mid-run** in `ai.miniforge.dag-executor.state-test/transition-task!-emits-event-test` — passes in isolation and final pre-commit run was clean. Unrelated to this change; pre-existing brick-isolation hazard around event-stream global state. Worth a separate look as `:fault`-classified follow-on.
- **Apache 2 license headers** on every file (matches `components/anomaly/src/ai/miniforge/anomaly/interface.clj` exactly). Layer-labeled comment headers in interface, contract, and core.

## Related Issues/PRs

- Builds on the merged `feat/anomaly-component` PR
- Will pair with H3 `boundary` (exception → anomaly wrapper; not in this PR)
- Companion `005 exceptions-as-data` standards rule (merged) prescribes the use pattern
- Linter implementation (`feat/exceptions-as-data-linter`) being submitted as a sibling PR

## Checklist

- [x] New `response-chain` component with public API
- [x] Malli schemas for `Step` and `Chain` colocated in `contract.clj`
- [x] Decomposed test files (one per behavior, nine total)
- [x] Apache 2 license header on every file
- [x] Layer-labeled comment headers
- [x] No `requiring-resolve`
- [x] Component never throws (anomaly-returning at boundary)
- [x] Workspace and project deps.edn registration
- [x] `bb pre-commit` green
