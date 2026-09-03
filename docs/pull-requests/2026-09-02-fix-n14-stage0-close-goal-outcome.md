# fix: refuse a `close-goal` whose outcome the engine cannot impose

## Overview

`close-goal` is the one N14 §3.2 operation whose status comes from the
transaction payload rather than from a table. That payload field was never
validated. An outcome outside `#{:accepted :rejected}` — or no outcome at all —
committed, advanced the workspace version, moved the goal's staleness clock,
and left the goal `:open`.

This PR adds a `check-outcome` validation stage that refuses those payloads as
`:anomalies.deliberation/invalid-outcome`, so `run/step` records a
`:transaction/rejected` event instead of a `:transaction/committed` one.

## Motivation

Every other status effect is derived from the operation via
`tx/status-effect`, which is what stops a transaction naming `:challenge` and
carrying `:accepted`. `close-goal` is the exception, and
`commit/apply-status` reads it as:

```clojure
(if (= :close-goal (:op operation))
  (tx/goal-outcomes (:outcome operation))
  (get tx/status-effect (:op operation)))
```

`tx/goal-outcomes` is a set used as a function, so anything outside it yields
nil. nil is also what the table yields for an operation with no status effect,
and one line later the two are indistinguishable — the `(and status ...)`
branch falls through to a plain `object/touch`.

Reproduced on 2026-09-02 against `798d53b7b`, with the full stage list
`(into validation/concurrency-stages guards/guard-stages)`:

| Payload | `validate` | After commit |
|---|---|---|
| `{:op :close-goal :targets #{"goal-1"} :outcome :maybe}` | `nil` | `:open`, touched-at 11, version 11 |
| `{:op :close-goal :targets #{"goal-1"} :outcome "accepted"}` | `nil` | same |
| `{:op :close-goal :targets #{"goal-1"} :outcome nil}` | `nil` | same |
| `{:op :close-goal :targets #{"goal-1"}}` | `nil` | same |
| `{:op :close-goal :targets #{"goal-1"} :outcome :accepted}` | `nil` | `:accepted`, run closes `:success` |

This is a quieter failure than the ones #1852 and #1858 closed. Those threw,
either at commit or out of the validator itself; a throw is at least visible.
Here the transaction commits, the event log records a success, and only the
object's status disagrees.

It does not stay contained to the one operation.
`termination/goals-terminal?` closes a run when every `:goal` object is
terminal, so a synthesizer that believes it closed the last goal leaves the run
to end on a budget boundary instead of on its §7 closing rule — and the log
gives no reason why.

## Changes in Detail

### `validation.clj`

`check-outcome` (Layer 1) refuses two payloads, both under
`:anomalies.deliberation/invalid-outcome` with a distinct `:reason` for
routing, following the `invalid-creation` / `invalid-links` precedent:

1. `:unknown-outcome` — a `close-goal` whose `:outcome` is not in
   `tx/goal-outcomes`. An absent outcome is refused under the same reason as an
   illegal one: both name a status the engine cannot impose, and both are
   repaired by supplying one it can. The value is carried in `:outcome` so the
   two cases stay distinguishable to a reader without splitting the routing.
2. `:inapplicable-outcome` — the field on any other operation. `commit`
   ignores it there by design, which stops it taking effect but does not report
   it, leaving the same silence in the other direction. An explicit nil names
   no status and is treated as absent rather than inapplicable.

The stage is validated against `tx/goal-outcomes` rather than
`object/status-model`'s `:goal` entry, because `goal-outcomes` is exactly what
`apply-status` reads.

It is placed last of the payload stages in `concurrency-stages`. Order among
those is a preference rather than a constraint — the stage reads one scalar
field of the operation it is handed — and it is last because it is the only one
whose fault is silent absorption at commit rather than a crash there. It stays
after `check-schema`, so an operation outside the vocabulary is reported as an
unknown operation rather than as a bad outcome.

The namespace docstring said "creation-payload conformance", which stopped
being the whole story when #1852 added `check-links`. It now says
"operation-payload conformance", which covers the three without enumerating
them.

### `commit_test.clj`

`closing-a-goal-requires-a-legal-outcome` already asserted the silent-absorption
behavior as if it were the intended end state. The assertion is still right —
commit must not invent a status for an outcome it cannot read — but the label
now says validation refuses the payload before commit sees it, so the test reads
as the layer beneath rather than as an endorsement.

## Scope

`close-goal` targeting an object that is not a goal is left alone. A
`{:op :close-goal :targets #{"question-1"} :outcome :accepted}` is skipped by
`object/legal-status?` and absorbed the same way. That is not a `close-goal`
problem: every entry in `tx/status-effect` has it, so
`{:op :answer-question :targets #{"claim-1"}}` absorbs identically. Closing it
means deciding whether a graph-reading stage refuses the mismatch or
`apply-status` throws on it, which is a larger change than this one and is
tracked separately.

## Testing Plan

Five tests in `validation_test.clj` and one in `run_test.clj`. Run from
`components/deliberation-workspace`:

```bash
clojure -M:test -e "(require 'ai.miniforge.deliberation-workspace.validation-test) (clojure.test/run-tests 'ai.miniforge.deliberation-workspace.validation-test)"
```

- `closing-a-goal-requires-an-outcome-the-engine-can-impose` — the four
  payloads from the table above.
- `a-legal-outcome-passes` — every member of `tx/goal-outcomes` still
  validates.
- `only-close-goal-carries-an-outcome` — the field is refused elsewhere;
  an explicit nil is not.
- `an-outcome-rejection-carries-the-reason-and-the-value` — `:reason` and
  `:outcome` are readable without parsing the message.
- `an-unusable-outcome-outranks-the-goal-it-would-close` — the payload stage
  precedes `check-targets`, and `check-schema` precedes both.
- `a-close-goal-the-engine-cannot-honor-is-routed-not-absorbed` (`run_test`) —
  the end-to-end shape: a `:transaction/rejected` event with the subtype, no
  `:transaction/committed` event, the version unmoved, and the goal untouched
  rather than touched-but-still-open.

Verified the tests fail without the fix: reverting the stage from
`concurrency-stages` produces 20 failures across the two namespaces; with it,
the component's 125 tests and 400 assertions pass. `bb lint:clj`,
`bb lint:stratum` and `bb commit-budget` (138/200) are clean;
`validation.clj` stays at three strata.

## Deployment Plan

Ships with the component. No migration: the component has no consumers outside
its own tests yet, so nothing downstream changes shape. Runs that previously
committed an unusable `close-goal` now log a rejection instead, which costs the
same activation budget and surfaces a fault that was previously invisible.

## Related Issues/PRs

- [#1858](https://github.com/miniforge-ai/miniforge/pull/1858) — refuses the
  operation payloads the validator could not read. Same class, same file;
  merged, and this branch is rebased onto main after it. `check-outcome` sits
  after the `check-id-fields` it added, which is the one payload-stage
  ordering that is a constraint rather than a preference.
- [#1852](https://github.com/miniforge-ai/miniforge/pull/1852) — an
  operation's own `:links`, and creates across a transaction.
- [#1851](https://github.com/miniforge-ai/miniforge/pull/1851) — made the
  commit path's agent-reachable failures routable.

## Checklist

- [x] Bug reproduced against `798d53b7b` before the fix
- [x] Tests confirmed failing without the fix, passing with it
- [x] Full component suite green (125 tests, 400 assertions)
- [x] `bb lint:clj`, `bb lint:stratum`, `bb commit-budget` clean
- [x] `validation.clj` still within the 3-stratum ceiling
- [x] Remaining gap named in Scope rather than silently widened
