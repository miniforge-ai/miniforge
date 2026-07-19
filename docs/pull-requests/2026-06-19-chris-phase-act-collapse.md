<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor: collapse the phase-outcome derivation into one Domain authority

## Overview

Follow-up to #1236. #1236 typed the observed layer — `:phase/outcome` gained
`:blocked` and `:redirected` — but deliberately left the internal phase-result
`:status` and the per-consumer derivations untouched to limit blast radius. This
pays down that deferred debt: the typed act's derivation, previously a private
helper in the workflow runner, now lives in one Domain function that the
producer path reads.

## Motivation

After #1236 the relationship between a phase's act and its control status was
derived independently in more than one place. `runner-events/phase-outcome`
reconstructed the typed act from `:status` plus marker keys
(`:phase/blocked-reason`, `:phase/transition-request`); the act was inferred from
field presence. Adding or changing an outcome value meant editing that derivation
in lockstep with the predicates that feed it. One authority removes the duplicate
derivation and gives the precedence a single, tested home.

## Changes in Detail

### Domain authority (`components/phase`)

- `phase-result/outcome` — the single derivation of `:phase/outcome` from a phase
  result and a success verdict. Precedence: a refusal (`:blocked`) dominates any
  other act; an outright failure dominates a pending redirect; a phase that did
  its work but hands control elsewhere is `:redirected`; otherwise `:success`.
  The `succeeded?` verdict is passed in by the caller (it folds in inner-agent
  failure and registry status normalization, which live above the Domain), so the
  function stays a pure reduction over the result's own markers. Exported through
  the phase interface.

### Producer path (`components/workflow`)

- `runner-events` deletes its private `phase-outcome`; `build-phase-event-data`
  calls `phase/outcome`. The Domain function carries the same precedence the
  private copy held, so the emitted `:phase/outcome` is byte-for-byte unchanged.

## Scope and Deliberate Exclusions

This is the "`:phase/outcome` becomes a pass-through of the internal status" arm
of the design choice, not the "replace `:status`" arm.

- **`:status` kept as the FSM control flag.** `registry/extract-status`
  normalizes step / chain / execution results too, not just phase results;
  replacing `:status` would bleed into dag-executor, loop, and gate. Out of scope.
- **Consumer projections accepted, not collapsed.** `tui-views`
  `persistence/phase-status` and the cli live-render already read the canonical
  `:phase/outcome`; they project it to a view vocabulary (`:blocked → :failed`,
  `:redirected → :success`). Sourcing that projection from a shared classifier
  would add a `tui-views → event-stream` dependency edge for ~8 lines of view
  code — declined (recorded decision; Simple-Made-Easy over the marginal DRY).
- **Per-phase leave-handler outcomes deferred.** The phase-software-factory
  leave-handlers (verify / review / implement / plan / release) build `:outcome`
  inline (`:success` / `:failure` only) on a second emission path
  (telemetry `emit-phase-completed!`, which reads `:outcome` off the result).
  Routing those through `phase/outcome` is a separate surface the brief did not
  enumerate; not folded in here.
- **Pre-existing `:skipped` emission gap noted, not fixed.** `phase-outcome`
  never returns `:skipped`; a skipped phase reports `:success`.
  `workflow-resume/extract-completed-phases` filters completed phases on
  `:success`, so faithfully emitting `:skipped` would change resume semantics.
  Left as-is, to be addressed with the resume filter together.

## Testing Plan

- New: `outcome` cases in `phase-result-test` — `:success`, `:failure`, refusal
  dominance (`:blocked` regardless of the success verdict), redirect-on-success
  (`:redirected`), and failure-over-redirect.
- Regression (behavior preserved): `phase-outcome-from-inner-result-test`,
  `tui-views/persistence-test`, `cli/workflow-runner/display-output-test`, and
  `workflow-resume/core-test` all green.
- Verified: `clj-kondo` clean; each commit within the 200-line budget;
  `bb pre-commit` (commit budget, `poly check`, lint, format, 316-test smoke set,
  graalvm) green on every commit.

## Deployment Plan

Product is unreleased — no compatibility shims. Pure refactor; the emitted
`:phase/outcome` and all consumer behavior are unchanged. No data migration.

## PR Layering

Two commits, each independently valid and under the 200-line commit budget:

1. `feat(phase)` — the `phase-result/outcome` authority + interface export + tests.
2. `refactor(workflow)` — `runner-events` delegates to `phase/outcome`.

## Related

- Predecessor: #1236 (`chris/typed-phase-acts`) — typed the observed layer; this
  collapses its derivation into the Domain.

## Checklist

- [x] `phase-result/outcome` authority + interface export + tests
- [x] `runner-events` delegates to `phase/outcome`
- [x] Consumer behavior preserved (persistence / display / resume tests green)
- [x] `clj-kondo` clean, `poly check` OK, commit budget respected
- [ ] Push and open PR (awaiting approval)
