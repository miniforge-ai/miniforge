# feat: add boundary component for exception → anomaly conversion

## Overview

Adds the third H-series cross-cutting primitive to miniforge OSS: `ai.miniforge.boundary`. Provides `execute-with-exception-handling` (and a short `execute` alias) — the canonical wrapper that catches thrown exceptions from libraries (DBs, HTTP clients, JVM I/O, parser failures, timeouts) and converts them into anomaly steps inside an existing `response-chain`, without bespoke try/catch noise at every call site.

Together with the merged `anomaly` (H1) and `response-chain` (H2) components, this completes the "exceptions are data" trio: anomaly is the shape, response-chain carries it through a flow, boundary catches throws at the edges.

## Motivation

The standards rule `005 exceptions-as-data` (merged) prescribes that non-boundary code returns anomalies as data. The companion linter (`feat/exceptions-as-data-linter`, merged) flags violations. The cleanup workstream (`refactor/exceptions-as-data-foundation-cleanup`, merged) started replacing throw sites with anomaly returns.

What was still missing: the canonical wrapper for the *boundary* itself — the place where exceptions from external libraries (which you cannot rewrite) get converted into the anomaly-shaped flow. Without this primitive, every boundary site reinvents try/catch, the conversion logic drifts, and the exception-data capture is inconsistent.

This PR is the wrapper. It is the third (and final) H-series primitive.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly shape and constructor
- `ai.miniforge.response-chain` (merged) — chain accumulator

## Layer

Foundations / cross-cutting primitive. New top-level component.

## What This Adds

- New component `components/boundary/`:
  - `deps.edn` — `:local/root` deps on `ai.miniforge/anomaly` + `ai.miniforge/response-chain` + malli
  - `src/ai/miniforge/boundary/contract.clj` — Malli schemas for the exception-category vocabulary plus the advisory `CheckFn` shape
  - `src/ai/miniforge/boundary/core.clj` — implementation, including the data-driven `category->anomaly-type` mapping table
  - `src/ai/miniforge/boundary/interface.clj` — public API
- Eight decomposed test files under `test/ai/miniforge/boundary/interface/`, one per behavior dimension:
  - `happy_path_test.clj`
  - `exception_to_anomaly_test.clj`
  - `category_classification_test.clj`
  - `exception_data_capture_test.clj`
  - `never_throws_test.clj`
  - `programmer_error_guard_test.clj`
  - `multi_step_composition_test.clj`
  - `ex_data_preservation_test.clj`
- Workspace registration: root `deps.edn` (`:dev`, `:test`) + each consumer project (`projects/miniforge/`, `projects/miniforge-core/`, `projects/miniforge-tui/`)

## Public API

```clojure
(execute-with-exception-handling category chain operation-key f & args)
;; Calls (apply f args). On success, append a successful step to chain.
;; On thrown exception, classify via category, build the appropriate
;; anomaly type, append an anomaly step, and return the chain.
;; Never throws — the chain absorbs the exception as data.

(execute category chain operation-key f & args)
;; Short alias.

exception-categories
;; #{:network :db :io :parse :timeout :unavailable :unknown}

(category->anomaly-type category)
;; :network    → :unavailable
;; :db         → :fault
;; :io         → :fault
;; :parse      → :invalid-input
;; :timeout    → :timeout
;; :unavailable → :unavailable
;; :unknown    → :fault

CheckFn
;; Advisory malli schema for higher-level wrappers.
```

## Anomaly data shape

The anomaly's `:anomaly/data` carries everything a triage tool needs:

```clojure
{:exception/type     "java.lang.RuntimeException"   ; class name
 :exception/message  "..."                           ; (.getMessage e)
 :exception/cause    "..."                           ; (-> e ex-cause ex-message), nil if absent
 :exception/data     {...}                           ; (ex-data e), nil if absent
 :boundary/category  :db}                            ; the supplied category
```

`(ex-data e)` from libraries propagates verbatim into `:exception/data`, so structured information from `ex-info`-throwing libraries (e.g. clj-http, datalevin) survives the conversion.

## Strata Affected

- `ai.miniforge.boundary.contract` — Malli schemas and the category vocabulary
- `ai.miniforge.boundary.core` — implementation + data-driven category mapping
- `ai.miniforge.boundary.interface` — public API

No existing component touched. Pure additive change.

## Testing Plan

- **`bb pre-commit` green:**
  - `lint:clj` clean
  - `fmt:md` clean
  - `test` — 3419 tests / 15052 passes / 0 failures / 0 errors. New component contributes 45 tests / 63 assertions.
  - `test:graalvm` — 6 tests / 478 assertions / 0 failures
- Eight decomposed test files map 1:1 to the per-behavior dimensions; each is independently exercisable.

## Deployment Plan

No migration required. New additive component. Existing miniforge code unchanged.

The boundary wrapper is opt-in — components that need it migrate one site at a time. The linter rule (`005 exceptions-as-data`) does not require usage of `boundary`; it only requires that non-boundary code returns anomalies. Boundary is the *sanctioned* way to convert thrown exceptions in the namespaces that ARE allowed to throw.

## Notes / Deviations

- **`:conformance` alias not updated.** The conformance gate carries a curated subset of components; `response-chain` is not in it either. Followed that precedent.
- **Argument order is `category chain operation-key f & args`** — category first, chain second. This makes naive `->` threading land the chain in the category slot. Tests use `as->` or explicit `let` rebinding when threading. Rich-comment example in `interface.clj` reflects this. No public API change.
- **`CheckFn` schema is advisory.** Boundary itself doesn't consume one; the schema is exposed for higher-level wrappers (HTTP-flavored, DB-flavored variants) that may layer on top later. This matches the spec's framing that boundary is the generic primitive.
- **Apache 2 license headers** on every new file. Layer-labeled comment headers in interface, contract, and core. Malli throughout. No `requiring-resolve`.
- **The component is itself a non-throwing boundary helper.** The one acceptable throw is a programmer-error guard for unknown category (similar to anomaly's vocabulary check). Verified by `programmer_error_guard_test.clj`.

## Related Issues/PRs

- Builds on the merged `feat/anomaly-component` and `feat/response-chain-component`
- Companion to the merged `005 exceptions-as-data` standards rule
- Companion to the merged `feat/exceptions-as-data-linter`
- Unblocks Wave 3+ wiring work (e.g. response-chain integration in thesium-workflows retrieval paths)

## Checklist

- [x] New `boundary` component with public API (`execute-with-exception-handling`, `execute`, `exception-categories`, `category->anomaly-type`, `CheckFn`)
- [x] Data-driven category mapping table (no nested ifs)
- [x] Anomaly data captures `:exception/{type,message,cause,data}` plus `:boundary/category`
- [x] Decomposed test files (eight, one per behavior)
- [x] Apache 2 license headers
- [x] Layer-labeled comment headers
- [x] Component never throws on runtime input (programmer-error guard for unknown category is the only acceptable throw)
- [x] Workspace and project deps.edn registration
- [x] `bb pre-commit` green
