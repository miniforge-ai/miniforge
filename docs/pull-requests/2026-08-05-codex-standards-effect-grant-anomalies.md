<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# test: cover anomaly propagation through the effect lifecycle

## Overview

Adds one regression test proving that `record/propose!` and
`record/advance!` return a store write failure unchanged instead of
reporting success. No production code changed.

This document originally described a broader fix. That fix did not land —
see [What did not land](#what-did-not-land) — and the document has been
corrected to describe the commit that did.

## Changes in Detail

Merged as `3d2361e4` ("fix: return anomalies from durable effect
boundaries (#1672)", 2026-08-11), which squash-merged two files:

| File | Change |
|------|--------|
| `components/effect-transaction/test/ai/miniforge/effect_transaction/store_test.clj` | Adds `lifecycle-writes-propagate-store-anomalies-test` (stratum 2). |
| `docs/pull-requests/2026-08-05-codex-standards-effect-grant-anomalies.md` | This document. |

The test redefines `store/create!` and `store/transition!` to return an
`:unavailable` anomaly, then asserts both lifecycle entry points return
that value unchanged. It guards against reintroducing a
`(do (store/create! dir t) t)` shape, which discards the store's return
value and reports success on a failed write.

The commit title names a fix the merged diff does not contain. The title
is preserved here as the durable identifier for `3d2361e4`; treat this
section, not the title, as the description of what changed.

## What did not land

The branch originally carried the fix the commit title names: six files
returning serialization and validation failures as anomaly values before
any write, across `effect-transaction` and `execution-grant`. A 2026-08-07
rebase dropped them. They patched `store/write!`, a function the durable
store no longer has. `store.clj` now delegates to `persistence.clj`, which
owns the file, lock, and link handling and in turn calls `codec.clj` at the
EDN boundary; `store.clj` does not reference the codec itself. Every
production file conflicted, and the conflicts were resolved by discarding
the changes rather than porting them.

The commit that performed that split is not identifiable from the current
history. The earliest commit in this repository (`e67c640`, 2026-08-07) is
a root commit with no parents, and it already contains the split layout,
so the restructure predates everything now visible. `git log` on these
paths reports `e67c640` only because history stops there — that is
truncation, not authorship.

No follow-up is required. `main` reaches the same guarantees by another
route:

- `record/propose!` and `record/advance!` validate against
  `schema/EffectTransaction` before writing, and return `store/create!` /
  `store/transition!` in tail position, so persistence anomalies reach
  callers.
- `persistence/persist!` wraps serialization in `try`/`catch`, and
  `pr-str` is evaluated before `spit`, so a rejected record never reaches
  disk.
- `interface/record-breach!` validates `schema/Breach`, and
  `interface/revoke-for-cause!` validates
  `[ExecutionGrant BreachObservation inst?]`, which makes the breach
  record built from those arguments valid by construction.

Coverage for the execution-grant half already exists in
`breach_test.clj`: `malformed-breach-is-refused-test`,
`storage-failures-are-returned-as-data-test`, and
`both-inst-types-round-trip-test`.

## Testing Plan

- Focused `effect-transaction` tests
- Normal pre-commit validation

## Deployment Plan

No migration or rollout is needed.

## Related Issues/PRs

- Base Branch: `main`
- Depends On: none
- Merged As: `3d2361e4`
- Context: the durable effect boundary on `main` already supplies the
  anomaly contract this branch set out to add; see
  [What did not land](#what-did-not-land)

## Checklist

- [x] Pre-commit checks passed
- [ ] Audit gap fixed — the guarantees hold on `main` today, but not
      because of this commit
