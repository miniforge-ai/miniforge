<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# feat: add anomaly component for canonical data-first error type

## Overview

Adds a new `ai.miniforge.anomaly` Polylith component to miniforge OSS, providing a single canonical anomaly shape,
constructor, predicate, and Malli schema. This is the foundational primitive for the "exceptions are data" discipline
that all miniforge-family Clojure repos will share.

## Motivation

Miniforge has accumulated multiple ad-hoc shapes for representing failures (assorted maps, `ex-info` payloads, scattered
`:error`/`:errors` conventions). Without a canonical anomaly type, downstream repos (thesium-workflows, miniforge-fleet,
future ports of ixi) end up redefining the same shape independently and drifting.

This component is the substrate fix: one Apache 2 component in miniforge OSS that every miniforge-family Clojure repo
can consume via vendoring. It pairs with the upcoming `response-chain`, `boundary`, and `content-hash` components to
give the family a shared error-handling and provenance vocabulary.

The architectural decision to add cross-cutting primitives **into** miniforge OSS rather than extracting them out into a
separate `miniforge-clj-commons` repo is captured in the broader memory-substrate refactor plan.

## Base Branch

`main`

## Depends On

None. This is the first of the H-series cross-cutting components.

## Layer

Foundations / cross-cutting primitive. New top-level component.

## What This Adds

- New component `components/anomaly/`:
  - `deps.edn` — minimal; depends on `metosin/malli 0.16.4` only.
  - `src/ai/miniforge/anomaly/contract.clj` — Malli `Anomaly` schema (`:closed true`), `anomaly-types` set, validation
    helpers.
  - `src/ai/miniforge/anomaly/interface.clj` — public API: `anomaly`, `anomaly?`, `Anomaly`, `anomaly-types`.
    Layer-labeled per stratified-design.
- Seven decomposed test files under `test/ai/miniforge/anomaly/interface/`, one per behavior dimension:
  - `constructor_test.clj`
  - `predicate_test.clj`
  - `schema_test.clj`
  - `type_vocabulary_test.clj`
  - `data_default_test.clj`
  - `timestamp_test.clj`
  - `round_trip_test.clj`
- Root `deps.edn` registration: adds `ai.miniforge/anomaly` `:local/root` dep and `components/anomaly/{src,test}` paths
  under `:dev` / `:test` / `:conformance` aliases.

## Public API

```clojure
(anomaly type message data)   ; constructor, returns canonical map
(anomaly? x)                  ; predicate
Anomaly                        ; Malli schema (closed)
anomaly-types                  ; set of standard type keywords
```

Anomaly map shape:

```clojure
{:anomaly/type    keyword?    ; one of anomaly-types
 :anomaly/message string?
 :anomaly/data    map?        ; defaults to {} when nil
 :anomaly/at      inst?}      ; populated at construction
```

Standard type vocabulary (mirrors cognitect anomalies):

```
#{:not-found :invalid-input :unauthorized :fault :unavailable
  :conflict :timeout :unsupported :fatal}
```

The constructor validates `type` against the vocabulary and throws `IllegalArgumentException` on unknown types. This is
one of the few legitimate `throw` sites: it is a programmer-error guard, not a runtime failure path.

## Strata Affected

- `ai.miniforge.anomaly.contract` — Malli schema and type vocabulary
- `ai.miniforge.anomaly.interface` — public API

No existing component touched. New top-level brick.

## Testing Plan

- `bb pre-commit` — full local gate: `lint:clj` + `fmt:md` + `test` + `test:graalvm`. **All passed.**
- `lint:clj` — 10 files, 0 errors, 0 warnings.
- `test` — Polylith picked up `anomaly` as the only changed brick: 26 tests, 68 assertions, 0 failures, 0 errors.
- `test:graalvm` — 6 tests, 465 assertions, 0 failures.

The seven test files map 1:1 to the per-behavior dimensions documented in the brief. Each dimension is independently
exercisable, so future agents can modify one without perturbing the others.

## Deployment Plan

No migration required. New additive component. Existing miniforge code is unchanged and unaffected.

Downstream consumption (thesium-workflows, miniforge-fleet, etc.) will land in subsequent PRs as those repos rewire
their local error helpers to consume `ai.miniforge.anomaly` directly via vendoring.

## Notes / Deviations

- **`workspace.edn` not modified.** Polylith auto-discovers `components/anomaly/` from disk; the workspace config lists
  per-project `:necessary` overrides only. Confirmed via `poly info` — new brick discovered automatically.
- **Root `deps.edn` was edited.** The changed-brick test runner that backs `bb test` runs under `clojure -M:dev:test`;
  those aliases are explicit allow-lists, so registration is required for tests to load.
- **No `bb gate` task in this repo.** The canonical local gate is `bb pre-commit` (documented in `agents.md`). All
  checks passed.
- **EDN round-trip uses Date precision.** `pr-str` of a `java.time.Instant` does not emit `#inst`; the test serializes
  via `java.util.Date` (millisecond precision) and a custom `#inst` reader rebuilds the Instant on the read side.
  Sub-millisecond precision is not representable in standard EDN.
- **Pre-existing `poly check` errors** in `bb-dev-tools` and `miniforge-tui` are present on main and unrelated to this
  change.

## Related Issues/PRs

- Will be followed by `feat/extract-content-hash-component` (sibling H-series component)
- Will be followed by `response-chain` and `boundary` components in the H-series
- Companion standards rule `feat/exceptions-as-data-rule` lands in `miniforge-standards`

## Checklist

- [x] New `anomaly` component with public API (`anomaly`, `anomaly?`, `Anomaly`, `anomaly-types`)
- [x] Malli schema in `contract.clj`
- [x] Decomposed test files (one per behavior, seven total)
- [x] Apache 2 license header on every file
- [x] Layer-labeled comment headers
- [x] No `requiring-resolve`
- [x] Root `deps.edn` registration in `:dev`/`:test`/`:conformance`
- [x] `bb pre-commit` green
