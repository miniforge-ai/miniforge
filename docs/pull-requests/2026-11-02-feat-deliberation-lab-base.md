<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-lab): standalone development lab base and project

## Overview

Last code PR for the deliberation development lab (snapshot `a345990`). It
adds:

- `bases/deliberation-lab`: the CLI, the model adapter, manifest validation,
  and run, verify and implementation hashing.
- the base, its runtime components and a development manifest, added to
  `projects/deliberation-lab` (created in #2344 so CI tests the components).

The reusable logic is in the `deliberation-harness` component from the
harness PRs (#2334, #2346).

## Motivation

The lab runs synthetic development comparisons under N15 §0.5 without
touching the canonical software-factory workflow. Under Polylith (dewey 210),
a base stays a thin entry point over component interfaces.
`lab` holds only this CLI's run orchestration (preflight, journal layout,
verification); the reusable parts are harness interface functions, and the
base requires no component internals (dewey 311).

## Changes in Detail

- `adapter`
  - Calls the model through the `llm` component's isolated Claude client
    (`:backend :claude :isolated? true`): no tools, no user settings, hooks
    or MCP servers. The snapshot built its own `claude` process instead.
  - Token usage comes from the provider envelope. The adapter uses the
    streaming path, which parses it; the buffered path substitutes zero
    usage when none is parsed. A response without positive input tokens is
    therefore a missing-usage failure, never a free call.
  - A model envelope with any field outside
    `#{:operations :inspect :solution :rationale}` is an invalid, charged
    payload.
  - A failed call, or one without usage, is an anomaly and is never
    retried.
  - The system prompt is a resource, now updated for `supersede` and
    `:supports` links.
- `manifest`: `defects` names every invalid field, including a wrongly
  shaped one or a manifest that is not a map, without throwing: mode, schema version,
  model, replicates, input cap, conditions, tiers (at least one working turn
  plus the closing turn) and price weights.
- `lab`
  - `execute!` checks the manifest and fixtures before any call, and builds
    the model call only from a manifest that passed. It pins the
    prompt, task and implementation hashes, journals the opened record,
    every cell and the closing report record, and writes `report.md`.
  - A run creates a new directory holding `journal/` and `report.md`. The
    journal reader merged in #2334 accepts record files only, so the report
    sits beside the journal, not inside it as in the snapshot, where
    `verify!` would refuse every run. The report is written through
    `file-durability`, so a write failure is an anomaly.
  - `verify!` checks the chain, that the journal opens and closes a run, and
    that every recorded board replays from its own transaction log.
  - `mock-call` for offline smoke runs.
- `main`: `smoke`, `run`, `verify`. Results and anomalies print as data; an
  anomaly exits non-zero. A wrong argument count prints the usage instead of
  throwing an arity exception.
- `projects/deliberation-lab` gains the base and the components `llm`
  needs, a `resources` path and a `:run` alias. Its `:necessary` list in
  `workspace.edn` stays. Root `deps.edn` gains the base paths.
- The harness interface exports `fixture-defects` and `read-edn-file`, so a
  manifest read failure is an anomaly.
- Every named constant carries an intent docstring (dewey 006).

Deviations from the snapshot:

- The `llm` component replaces a hand-built `ProcessBuilder` call to the
  `claude` CLI.
- Every failure is an anomaly (dewey 005).
- No progress `println`; the journal is the record.
- The snapshot's own test runner is dropped: `poly test` covers the base.

Known limitation: the snapshot passed `--max-budget-usd` to the CLI as a
per-call dollar guard, and the `llm` client exposes no equivalent. Spend is
still bounded per cell by the turn allowance and the normalized-cost
ceiling, which is checked between calls. As in the snapshot, a single call
can cross the ceiling, and a cell that does cannot succeed. A hard
pre-reserved budget remains a prerequisite for N15 budget conformance
(N15 Annex A).

## Testing Plan

- `manifest_test`: each defect is named; the gate mode is refused.
- `lab_test`, marked `:integration` because it writes a temporary journal:
  - a smoke run journals into `journal/`, writes `report.md` beside it,
    verifies and replays;
  - a run refuses to reuse its directory;
  - an edited record fails verification;
  - an invalid or non-map manifest is refused before any call is built;
  - a response without provider usage is a failure;
  - forged metering in model output makes the payload invalid;
  - enums decode;
  - a wrong argument count is a usage error.

Ran locally on plain Clojure 1.12 (Clojars is unreachable from the authoring
sandbox), with scratch stand-ins outside the repo for the `llm` namespace
(no calls are made) and for cheshire's JSON parsing:

- `manifest_test` and `lab_test`: 7 tests, 32 assertions, 0 failures. The
  smoke test failed before the `journal/` change, the usage test before the
  argument-count change, and the metering, refusal and shape tests before
  the review fixes.
- clj-kondo 2026.10.26 (the CI version) on `bases components development/src`:
  0 errors.

`adapter/decode` against real cheshire, `poly test` and the Polylith check
ran only in CI. The poly test runner does not filter on `:integration`, so
the smoke run is part of CI.

## Deployment Plan

None. A standalone development project; nothing in the shipped products
depends on it.

## Related Issues/PRs

- Builds on the harness PRs (#2334, #2346), the lab project (#2344) and
  the workspace stack (#2330, #2331, #2332, #2335).
- Next: the architecture atlas docs and the N14/N15 Annex A updates.

## Checklist

- [x] Model calls through the `llm` component
- [x] No throws on data paths
- [x] Every refusal happens before any provider call
