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
  - A pass or a rejection marks the participant quiet; a commit clears the
    round.
  - New `close`: at a budget boundary, runs one closure turn for the first
    synthesizer when `:workspace/closure-reserve` is positive and activation
    allowance remains. It records `:termination/closure` as `:executed` or
    `:unfunded`.
  - A turn whose read scope withholds a goal or `:hard` constraint is
    refused before the model runs (N14 §4). It is not charged, it counts as
    quiet, and the rotation moves on.
  - `:workspace/stages` is gone; admission's stage chain is fixed.
- `scheduler`
  - `:workspace/participant-roles` maps participants to responsibilities.
  - Eligibility is keyed by responsibility.
  - Round-robin and each tier rotate after the last actor.
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
  rotates among participants that share a responsibility.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox): all 13 deliberation-workspace
suites, 169 tests, 543 assertions, 0 failures. `poly test`, clj-kondo and
the Polylith check ran only in CI.

## Deployment Plan

None. Experimental component with no production caller.

## Related Issues/PRs

- Base: #2331, which builds on #2330 and #2329. Contract: #2328.
- Next: `bases/deliberation-lab` and `projects/deliberation-lab`.

## Checklist

- [x] The activation never sees the workspace
- [x] Closure inside the allowance
- [x] Every new rule has a test
