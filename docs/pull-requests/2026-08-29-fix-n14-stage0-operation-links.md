# fix: validate an operation's own `:links`, and creates across a transaction

## Overview

Three agent-reachable defects in `components/deliberation-workspace` cleared
`validation/validate` and then either crashed the engine partway through
`commit/commit` or corrupted the object graph in silence. This PR refuses all
three in the validation pipeline, as anomalies routed by subtype.

## Motivation

[#1851](https://github.com/miniforge-ai/miniforge/pull/1851) added
`check-creates`, which reads the `:creates` payload of an operation, and
`assert-known-targets`, which fails fast on a target that commit should never
see. It deliberately left three gaps, all of the same class: input that an
activation controls, reaching the engine as an exception or as graph corruption
rather than as a routable rejection.

Reproduced on 2026-08-27 and again on 2026-08-29 against `f2b0382e0`:

| Payload | Before |
|---|---|
| `{:op :attach-evidence :targets #{"claim-1"} :links {:bogus #{"e-1"}}}` | validates, then `IllegalArgumentException: Unknown link type: :bogus` |
| `{:op :attach-evidence :targets #{"claim-1"} :links {:supports :e-1}}` | validates, then `IllegalArgumentException: Don't know how to create ISeq from: clojure.lang.Keyword` |
| `{:op :attach-evidence :targets #{"claim-1"} :links [:supports "e-1"]}` | validates, then `UnsupportedOperationException: nth not supported on this type: Keyword` |
| two sibling operations creating `"claim-1"` | validates and commits; only the second object survives |

The third of the originally-listed defects — a `:creates` spec with no `:id`,
landing in `:workspace/objects` under a nil key — is already closed. #1851's
review rounds added the `:blank-id` reason to `creation-defect` after the gap
list was written. Verified, not assumed: the repro now returns
`:anomalies.deliberation/invalid-creation` with `:reason :blank-id`.

## Changes in Detail

### `validation.clj`

1. `link-defect` (Layer 0) — the one reading of a `:links` map for both
   writers: `object/new-object` on a `:creates` spec, and
   `commit/apply-links` on the map an operation carries itself. Extracted
   from `creation-defect`, which previously held the only copy. Total over
   arbitrary EDN, `keys` on a non-map included.
2. `check-links` (Layer 1) — a new stage applying that reading to the
   operation's own `:links`, rejecting as
   `:anomalies.deliberation/invalid-links` with the specific `:reason` in
   `:anomaly/data` for routing.
3. `check-creates` — the duplicate-id check now reads the whole transaction
   through `:siblings` rather than this operation alone, subsuming the
   intra-operation case rather than sitting beside it. Two filters stand in
   front of the count: siblings whose `:creates` is not a sequence are
   skipped, and specs the engine could not construct are dropped.
4. `concurrency-stages` — `check-links` runs after `check-creates`, so both
   payload stages precede the three that read the object graph.

`creation-defect` and `link-defect` are siblings at Layer 0 composed by
`check-creates`, not one calling the other: chaining them added a fourth
layer to the namespace, which SL003 refuses.

### Why the sibling-collision check, after #1851 declined it

That PR left cross-operation id collisions undetected on the grounds that
blaming one of the two operations is arbitrary. The objection does not
survive contact:

- A rejected transaction is discarded whole (§3.4), so no operation is
  singled out for a penalty. `:op` records where the pipeline noticed; the
  message names the transaction as what is at fault.
- `:tx/operations` is a vector, so which operation notices is fixed by the
  payload rather than by iteration order. `:ids` is ordered by first
  appearance for the same reason.
- The failure is identical to the intra-operation one the same stage already
  refuses. `commit` reduces every operation onto one accumulator, so two
  creates at one id overwrite each other whichever operations carry them.

Counting only constructable specs was found in self-review, not by a test: a
sibling carrying two id-less specs would otherwise collide with itself at the
id nil and be reported as a duplicate, when the fault it will be rejected for
on its own turn is the missing id.

### Documentation

The docstrings this PR touches were trimmed to the current contract per
`documentation/documentation-discipline` (600). The arrival story — what
threw, when, and which PR left which gap — lives in this document, which is
where that rule puts it.

## Testing Plan

Run from `components/deliberation-workspace`:

```bash
clojure -M:test -e "(require 'ai.miniforge.deliberation-workspace.validation-test) (clojure.test/run-tests 'ai.miniforge.deliberation-workspace.validation-test)"
```

Added:

- `operation-links-the-engine-cannot-write-are-refused` — five payload shapes,
  including the string destination that would not have crashed at all.
- `an-operation-link-rejection-carries-the-reason-it-failed` — `:reason` is
  readable without parsing the message.
- `well-formed-operation-links-pass` — the stage refuses malformed edges, not
  linking.
- `a-malformed-payload-outranks-a-missing-target` — stage ordering.
- `a-transaction-may-not-create-two-objects-at-one-id` — sibling collision,
  the id-less-sibling misroute, and the non-sequential sibling.
- `an-edge-the-engine-cannot-write-is-routed-not-thrown` (run_test) — the
  rejection reaches the event log as `:transaction/rejected`, and a valid
  create in an earlier operation of the same transaction is discarded with
  it.

All 107 tests / 318 assertions in the component pass. `bb commit-budget`,
`bb lint:clj`, `bb lint:stratum` and the pre-commit suite pass on each commit.

## Deployment Plan

Library change inside one component; no migration, no configuration. Ships
with the next merge to `main`.

## Related Issues/PRs

- [#1851](https://github.com/miniforge-ai/miniforge/pull/1851) — the
  `check-creates` stage and `assert-known-targets`, whose out-of-scope gaps
  this closes.
- [#1841](https://github.com/miniforge-ai/miniforge/pull/1841) — the Stage 0
  run loop the routing test asserts against.

## Checklist

- [x] All three reported defects reproduced before the fix
- [x] Operation-level `:links` rejected for unknown edge type, scalar
      destination, and non-map shape
- [x] Predicates total over arbitrary EDN — a non-map `:links` is refused,
      not thrown on
- [x] Rejections carry an `:anomalies.deliberation/...` subtype and route
      through `run/step`
- [x] Cross-operation id collisions refused, with the blame question resolved
- [x] Component test suite green
- [x] Docstrings held to rule 600
