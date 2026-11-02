<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-workspace): participant identity and revisable records

## Overview

Second PR in the stack that lands the deliberation-workspace snapshot
(`a345990` on `claude/wip-deliberation-moved-from-desktop`). This is the
domain layer of `components/deliberation-workspace`: the per-operation
effects and checks that N14 0.3.0 (the specs PR before this one) amends.
Semantic admission, the run loop, the scheduler and termination follow in
the next PR.

## Motivation

N14 0.3.0 makes adoption a working choice rather than proof (§2.3), keys
challenge caps and idempotency on runtime-established participants (§3.4,
§3.5), adds `supersede` (§3.2), and forbids projections from revealing
hidden objects (§4). On main, an `:accepted` object is terminal, so nothing
can challenge it or add evidence to it. A refinement never changed the
statement. Conflicts were never resolved. Edges in a `:none`-visibility
projection named hidden ids.

## Changes in Detail

- `transaction`
  - Adds `supersede` (exclusive class, effect `:superseded`).
  - `:replacement` joins the id fields.
  - New `revisions` and `reopening-operations` sets.
  - New `with-status` / `with-content`: the pure effect of one operation on
    one target. They live here rather than in `commit`, which would otherwise
    need a fourth real layer (rule 210).
- `validation`
  - No operation of any class may act on a terminal target. The one exception:
    an `:accepted` target stays open to `challenge` and `attach-evidence`.
  - Only the synthesizer or the user may supersede a decision.
  - `supersede` may target only claims, hypotheses, plans and decisions. Its
    replacement may be created earlier in the same transaction.
  - New `check-statement`: revisions and answers carry text.
  - New `check-replacement`: `supersede` names exactly one live replacement
    of the same type, and no other operation names one.
  - The participant is passed through the validation context.
- `commit`
  - Created objects carry `:object/participant`.
  - Challenges record their participant, reason, resolution and evidence.
  - A challenge marks an adopted record `:object/contested?` and leaves it
    `:accepted`. Adopting a contested record keeps the marker.
  - A revision replaces the statement and appends the prior one to
    `:object/history`.
  - An answer is recorded as `:object/answer`.
  - A supersession records `:object/superseded-by`.
- Message catalog: three new keys (`missing-statement`, `invalid-replacement`,
  `inapplicable-replacement`). Three guard messages now say "Participant" or
  name the new backing option.
- `guards`
  - A challenge is also backed by a non-blank `:reason` plus `:resolution`.
  - Idempotency compares the whole payload per participant.
  - The open-challenge cap counts per participant.
  - `:challenge/role` is renamed `:challenge/participant`.
- `conflict` (a minimal change to main's `derive-conflicts`)
  - `:accepted` objects still take part in contradictions.
  - An open conflict whose pair stops being live is marked `:resolved`; it
    stays in the graph as history.
- `projection`
  - `:none` visibility matches the viewer against the object's participant.
  - Edges and `:object/superseded-by` pointing at hidden objects are removed.

Deviations from the snapshot:

- `supersede` uses an explicit `:replacement` field (see the specs PR).
- An answer no longer overwrites the question's statement: the question
  stays readable, and the answer sits beside it.
- A resolved conflict never reopens. Every status that ends liveness is
  terminal, so reopening could not happen.
- The snapshot's long docstrings and layer headings, which it had removed,
  are kept.

## Testing Plan

New tests in `commit_test`, `conflict_test`, `guards_test`, `projection_test`
and `validation_test` cover each behaviour above. Existing tests that used a
revision without a statement now supply one. One test that relied on
`:accepted` being closed to `attach-evidence` now uses `:superseded`, and
adds the accepted case as positive coverage.

Ran locally on plain Clojure 1.12 with a classpath of the component sources
(Clojars is unreachable from the authoring sandbox, so a local stand-in
replaced malli): all 12 deliberation-workspace suites, 147 tests,
481 assertions, 0 failures. `poly test`, clj-kondo and the Polylith check ran
only in CI.

## Deployment Plan

None. The component is experimental (N14 §0.4) and has no production caller.

## Related Issues/PRs

- Depends on the N14/N15 specs PR (#2328) for the contract, and on the
  message catalog PR (#2329), which is this PR's base branch.
- Next: semantic admission, run loop, scheduler and termination.

## Checklist

- [x] Each behaviour has a test
- [x] Layer headings consistent with same-file references
- [x] No new dependencies
