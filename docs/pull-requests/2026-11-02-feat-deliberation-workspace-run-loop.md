<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-workspace): participant run loop, closure reserve, round quiescence

## Overview

Last component PR in the stack that lands the deliberation-workspace snapshot
(`a345990`). The run loop now admits every proposal through the trusted
boundary from the previous PR. It schedules participants rather than
responsibilities, funds forced synthesis from a reserve inside the activation
budget, and closes on round-based quiescence.

## Motivation

N14 0.3.0 requires:

- **§0.5 and §4.1:** an activation receives its projection, never the
  workspace, and identity and basis come from the runtime.
- **§5.2:** responsibilities may be shared by several participants, and the
  meta-watchdog runs only on events.
- **§6.3:** the closure allowance sits inside the total allowance.
- **§7:** success means every goal accepted, and rejected goals complete under
  a separate rule. Quiescence is a full round in which every ordinary
  participant passes with no structural work waiting.

On main, the loop hands the raw workspace to the activation, trusts the
transaction it returns, schedules by role, treats any terminal goal set as
success, and counts quiescence by object growth.

## Changes in Detail

- `run`
  - New `activate-participant`: one work turn. The request carries
    `:role :participant :activation :basis :projection :closing? :feedback`,
    and the proposal goes through `admission/admit`.
  - `:feedback` reports the last transaction's status. A rejection's reason
    is included only when the participant sees every object (full visibility,
    no read scope), because otherwise the reason could reveal whether a hidden
    object exists. Trusted events always keep the reason.
  - A pass or a rejection marks the participant quiet. Pass and
    frame-refusal markers belong to the workspace version they were recorded
    at, so any commit starts a new round, including one made directly
    through `admit` or `replay-transaction`.
  - An admitted proposal that names an id the participant was not shown, and
    does not create itself, is refused exactly like an unknown target.
    Admission checks ids against the whole graph. Without this guard, under
    `:visibility :none` or a read scope, a participant could act on a hidden
    object by guessing its id, and the commit would confirm the object exists
    (N14 §4.4).
  - `activate-participant` enforces the budget itself, so the public one-turn
    entry point cannot bypass `run`'s checks. An ordinary turn past the
    closure reserve, or a closure turn with no allowance left, is recorded as
    `:activation/unfunded` without calling the model or charging anything.
  - New `close`: at a budget boundary, runs one closure turn for the first
    synthesizer when `:workspace/closure-reserve` is positive and activation
    allowance remains. It records `:termination/closure` as `:executed` or
    `:unfunded`.
  - A turn whose read scope withholds a goal or `:hard` constraint (the
    `:projection/withheld-frame` from #2337) is refused before the
    model runs (N14 §4). It is not charged, it counts as quiet, and the
    scheduler passes the participant over until the next commit. A refused
    closure turn is recorded as `:refused`.
  - `:workspace/stages` is gone; admission's stage chain is fixed.
  - `run` returns an `:invalid-input` anomaly unless
    `:workspace/closure-reserve` is a non-negative integer, because a
    negative reserve would enlarge ordinary work past the budget.
- `scheduler`
  - `:workspace/participant-roles` maps participants to responsibilities.
  - Eligibility is keyed by responsibility.
  - Round-robin and each tier rotate over the full roster from the last
    actor, taking the first eligible participant. A skipped (frame-refused)
    last actor therefore never sends the rotation back to the start.
  - The meta-watchdog is excluded from ordinary rounds and from the
    structural tiers.
  - Exposes `triggered-activation`, `ordinary-participants` and
    `responsibility-of`.
- `termination`
  - New rule `:completed-with-rejected-goals`.
  - The activation budget for ordinary work stops at the closure reserve.
  - Quiescence is round-based; `:workspace/quiet-rounds` and
    `default-quiescence-rounds` are removed.
- `interface` adds `activate-participant` and `run`.

Deviations from the snapshot:

- A rejection counts toward the quiet round, because §7 says "without a
  committed contribution".
- Tiers rotate among eligible participants, not only round-robin.
- The meta-watchdog is outside the round (from #2328 review).
- The activation target is passed into the turn, rather than patched onto the
  last event afterwards.

Not implemented, and recorded in N14 Annex A: the meta-watchdog activation
before deadlock closure, and cost metering.

## Testing Plan

- `run_test`: activations return bare proposals, because a proposal carrying
  its own activation or basis is now rejected. New tests cover:
  - the request never containing the workspace;
  - the pass round followed by the funded closure turn;
  - an unfunded closure;
  - participants that share a responsibility.
- `termination_test`: round-based quiescence (including the meta-watchdog and
  waiting structural work), rejected goals, and the closure reserve.
- `scheduler_test`: round-robin skips the event-only participant; a tier
  rotates among participants that share a responsibility; a frame-refused
  last actor does not reset the rotation.
- `run_test` also covers refusing a negative closure reserve.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox): all 12 deliberation-workspace
suites, 191 tests, 587 assertions, 0 failures. clj-kondo 2026.10.26 (the CI
version) on the component: 0 errors. `poly test` and the Polylith check ran
only in CI.

## Deployment Plan

None. Experimental component with no production caller.

## Related Issues/PRs

- Base: main, after #2337 merged (projection read scope and withheld frame,
  split out of this PR for size), which built on #2331, #2330 and #2329.
  Contract: #2328.
- Next: `bases/deliberation-lab` and `projects/deliberation-lab`.

## Checklist

- [x] The activation never sees the workspace
- [x] Closure inside the allowance
- [x] Every new rule has a test
