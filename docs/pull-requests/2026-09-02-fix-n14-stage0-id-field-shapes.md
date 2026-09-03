# fix: refuse operation payloads the validator cannot read

## Overview

Four agent-reachable payload shapes threw `IllegalArgumentException` out of
`validation/validate` itself, and a fifth was accepted and misrouted. This PR
refuses all five as anomalies carrying an `:anomalies.deliberation/...`
subtype, so `run/step` records a `:transaction/rejected` event instead of
dying.

## Motivation

[#1852](https://github.com/miniforge-ai/miniforge/pull/1852) closed the
operation-level `:links` gaps and the cross-transaction duplicate creates.
The defects here are the same class — activation-controlled input reaching the
engine as an exception rather than as a routable rejection — but they fail
harder. A commit-time throw at least happens after `validate` returned, so a
stage could in principle have caught the payload. These throw from inside the
validation pipeline: no stage returns, `run/step` never receives an anomaly,
and there is nothing for the scheduler to route or log.

Reproduced on 2026-08-29 and again on 2026-09-02 against `798d53b7b`, with
the full stage list `(into validation/concurrency-stages guards/guard-stages)`:

| Payload | Before |
|---|---|
| `{:op :refine-claim :targets :claim-1}` | `IllegalArgumentException: Don't know how to create ISeq from: clojure.lang.Keyword`, thrown out of `validate` |
| `{:op :challenge :targets #{"claim-1"} :evidence :e-1}` | same throw, from the guard stage |
| a sibling `{:op :propose-experiment :discriminates :h-1}` | same throw, while the *challenge* was being validated |
| `{:tx/operations :assert-claim}` | same throw, before any stage ran |
| `{:op :refine-claim :targets "claim-1"}` | validated, then rejected as `:unknown-target` with `:missing #{\a \c \i \l \- \m \1}` |

The last row is the string-walking hazard #1852 named for `:links`
destinations, in the field that decides which objects an operation touches. A
string is seqable, so `(set "claim-1")` yields seven single-character ids and
the operation is reported against ids no activation named.

The fourth row was found in self-review of the fix for the first three, not
in the original report. `run/step` hands an activation's return value straight
to `validate`, which iterates `:tx/operations` with `some`.

## Changes in Detail

### `transaction.clj`

`id-fields` names the three operation fields that carry object ids —
`:targets`, `:evidence`, `:discriminates`. It lives beside `touched-ids`,
which reads the first of them, in the namespace that already holds the rest of
the §3.2 payload vocabulary.

### `validation.clj`

1. `id-listing-defect` (Layer 0) — the one reading of an id-naming field, as
   `[reason data]` or nil. Legal is absent, nil, or a collection of non-blank
   strings. A scalar and a map are refused as `:non-collection-ids`; a
   collection holding anything that is not a usable id is `:unusable-id`. A
   string falls in the first case, which is the point: it is refused for its
   shape rather than walked into characters. Total over arbitrary EDN.
2. `id-listings` (Layer 0) — enumerates `[operation field value]` across a
   whole transaction. Operations that are not maps are skipped rather than
   interpreted, for the reason `proposed-specs` skips a non-sequential
   `:creates`: `contains?` throws on a scalar, and reading fields out of a
   payload that is not a map moves the crash rather than removing it.
3. `check-id-fields` (Layer 1) — the stage, leading the payload stages.
4. `validate` — `:tx/operations` must be nil or sequential.
5. `concurrency-stages` — `check-id-fields` runs immediately after
   `check-schema`.

`id-listing-defect` and `id-listings` are siblings at Layer 0 composed by the
Layer 1 stage, not one calling the other. Chaining them would add a fourth
layer to the namespace, which SL003 refuses — the same constraint #1852 hit
with `creation-defect` and `link-defect`.

### `guards.clj`

Docstring only. `backed?` reads `:evidence` and a sibling's `:discriminates`
unscreened, and now says why that is safe: the concurrency stages establish
the shape before any guard runs, which is why the guards must be composed onto
that chain rather than run alone.

### Why the whole transaction is scanned, and why the sibling is blamed

`backed?` consults a *sibling's* `:discriminates` while validating a
challenge. Stages run operation-major — every stage for operation 1, then
every stage for operation 2 — so a stage reading only its own operation would
reach a malformed sibling field before the operation carrying it had a turn.
The scan therefore covers `:siblings`, as `check-creates` already does for
duplicate ids.

This inverts #1852's blame convention, deliberately. There, `:op` records
where the pipeline *noticed*, because a duplicate id is a property of the
transaction and not of either operation. Here the malformed field belongs to
one operation, `:tx/operations` is a vector so which one is fixed by the
payload, and naming the operation that noticed would describe a fault it does
not have: for the third repro row above, `:challenge` would be blamed for
`:propose-experiment`'s `:discriminates`.

Left unfixed, the alternative — skipping the unreadable sibling and letting
`backed?` return false — reports `:bare-challenge`, which is true but useless:
it does not say that the experiment meant to back the challenge was
unreadable.

### Why the transaction-level check is not a stage

No stage can run until `validate` has iterated `:tx/operations`, so the check
sits in `validate` itself. Sequential rather than merely a collection, for the
reason `check-creates` requires it of `:creates`: a set has no first
operation, so which one the pipeline reported would vary between runs over
identical input. A transaction with no `:tx/operations` at all is empty, not
malformed, and still validates clean.

### Scope of the `:evidence` tightening

No reader requires `:evidence` to name ids — `backed?` only tests it for
non-emptiness. Holding it to the same shape as `:targets` is a tightening
beyond what is needed to stop the crash. It is kept because the field names
evidence objects in every existing use, and because `:evidence [nil]` would
otherwise back a challenge with nothing.

## Testing Plan

Run from `components/deliberation-workspace`:

```bash
clojure -M:test -e "(require 'ai.miniforge.deliberation-workspace.validation-test) (clojure.test/run-tests 'ai.miniforge.deliberation-workspace.validation-test)"
```

Added to `validation_test.clj`:

- `id-fields-that-are-not-collections-of-ids-are-refused` — the three
  reported scalars plus a number and a map.
- `a-string-of-ids-is-refused-rather-than-walked` — the misroute, asserted on
  the reason rather than the message.
- `an-id-field-rejection-names-the-field-and-the-reason` — routing reads both
  without parsing prose.
- `a-malformed-id-field-is-blamed-on-the-operation-carrying-it` — the sibling
  case, asserting `:propose-experiment` and not `:challenge`.
- `well-formed-id-fields-pass` — sets, vectors, lists, absent, and nil.
- `an-unusable-id-outranks-the-targets-it-would-be-looked-up-as` — ordering
  against `check-targets`.
- `an-operation-that-is-not-a-map-is-left-to-schema-conformance` — the
  regression the sibling scan introduced and this fixes.
- `a-transaction-whose-operations-are-not-a-sequence-is-refused` — scalar,
  map and set, plus the empty transaction that stays legal.

Added to `guards_test.clj`:

- `the-fields-the-backing-check-reads-are-routed-not-thrown` — both guard-side
  shapes over the composed pipeline, and that the shape fault outranks the
  `:bare-challenge` that would be read out of it.

Added to `run_test.clj`:

- `an-unreadable-id-field-is-routed-not-thrown` — the rejection reaches the
  event log as `:transaction/rejected`, the clock does not advance, and a
  valid create in an earlier operation is discarded with it.
- `an-activation-returning-a-malformed-transaction-is-routed` — the same for
  the transaction-level shape.

All 118 tests / 368 assertions in the component pass. `bb commit-budget`,
`bb lint:clj`, `bb lint:stratum` and the full pre-commit suite pass on each of
the two commits.

## Deployment Plan

Library change inside one component; no migration, no configuration. Ships
with the next merge to `main`.

## Related Issues/PRs

- [#1852](https://github.com/miniforge-ai/miniforge/pull/1852) — operation
  `:links` and cross-transaction duplicate creates, whose blame convention and
  string-walking hazard this builds on.
- [#1851](https://github.com/miniforge-ai/miniforge/pull/1851) — the
  `check-creates` stage this one is ordered against.
- [#1841](https://github.com/miniforge-ai/miniforge/pull/1841) — the Stage 0
  run loop the routing tests assert against.

## Checklist

- [x] All five payload shapes reproduced before the fix, and re-checked after
- [x] `:targets`, `:evidence` and `:discriminates` held to one shape
- [x] Predicates total over arbitrary EDN, non-map operations included
- [x] Rejections carry an `:anomalies.deliberation/...` subtype and reach the
      event log through `run/step`
- [x] Blame lands on the operation carrying the malformed field
- [x] `validation.clj` still at 3 strata (SL003)
- [x] Component test suite green
