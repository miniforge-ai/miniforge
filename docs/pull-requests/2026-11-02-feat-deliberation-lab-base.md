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
- `projects/deliberation-lab`: the standalone project with its development
  manifest.

The reusable logic is in the `deliberation-harness` component from the
previous two PRs.

## Motivation

The lab runs synthetic development comparisons under N15 §0.5 without
touching the canonical software-factory workflow. Under Polylith (dewey 210),
a base stays a thin entry point over component interfaces.

## Changes in Detail

- `adapter`
  - Calls the model through the `llm` component's isolated Claude client
    (`:backend :claude :isolated? true`): no tools, no user settings, hooks
    or MCP servers. The snapshot built its own `claude` process instead.
  - Token usage comes from the provider envelope.
  - A model envelope with any field outside
    `#{:operations :inspect :solution :rationale}` is an invalid, charged
    payload.
  - A failed call, or one without usage, is an anomaly and is never
    retried.
  - The system prompt is a resource, now updated for `supersede` and
    `:supports` links.
- `manifest`: `defects` names every invalid field: mode, schema version,
  model, replicates, input cap, conditions, tiers (at least one working turn
  plus the closing turn) and price weights.
- `lab`
  - `execute!` checks the manifest and fixtures before any call. It pins the
    prompt, task and implementation hashes, journals the opened record,
    every cell and the closing report record, and writes `report.md`.
  - `verify!` checks the chain, that the journal opens and closes a run, and
    that every recorded board replays from its own transaction log.
  - `mock-call` for offline smoke runs.
- `main`: `smoke`, `run`, `verify`. Results and anomalies print as data; an
  anomaly exits non-zero.
- `projects/deliberation-lab` lists every brick the base needs, including
  `llm`'s transitive components, and provides a `:run` alias.
  `workspace.edn` gains the `dlab` alias. Root `deps.edn` gains the base
  paths.
- The harness interface exports `fixture-defects`.

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
  - a smoke run journals, writes `report.md`, verifies and replays;
  - a run refuses to reuse its directory;
  - an edited record fails verification;
  - an invalid manifest is refused with no call made;
  - forged metering in model output makes the payload invalid;
  - enums decode.

Ran locally on plain Clojure 1.12 (Clojars is unreachable from the authoring
sandbox):

- `manifest_test` in full: 2 tests, 7 assertions, 0 failures.
- `lab_test`'s smoke, verify, tamper and refusal cases, run with local
  stand-ins for the `llm` and cheshire namespaces.

`adapter/decode` needs real cheshire, so its tests ran only in CI, as did
`poly test`, clj-kondo and the Polylith check.

## Deployment Plan

None. A standalone development project; nothing in the shipped products
depends on it.

## Related Issues/PRs

- Builds on the harness PRs (#2334 and the experiment PR) and on the
  workspace stack (#2330–#2332, #2335).
- Next: the architecture atlas docs and the N14/N15 Annex A updates.

## Checklist

- [x] Model calls through the `llm` component
- [x] No throws on data paths
- [x] Every refusal happens before any provider call
