<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat(deliberation-workspace): trusted semantic admission

## Overview

Third code PR in the stack that lands the deliberation-workspace snapshot
(`a345990`). It adds the admission boundary N14 0.3.0 requires: the runtime
supplies identity, activation and basis, while a proposal supplies only
operations. The operations are validated in order against a private
candidate and published as one version. It also adds the component's first
`interface` namespace. The run loop, scheduler and termination changes
follow in the next PR.

## Motivation

N14 §0.5 and `N14.WS.7` require participant, responsibility, activation and
basis to come from the runtime, never from model output. §3.4 requires each
operation to enforce its creation and target types, required fields and
reference integrity. It also forbids model-supplied authority, policy or
unattested provenance, requires goal closure to name an adopted covering
decision, and requires candidate validation to see earlier operations in the
same transaction without publishing partial state. Main has none of these
checks.

## Changes in Detail

- `admission` (new): `admit [workspace proposal context]`. Returns
  `{:workspace :transaction}` or a rejection anomaly, leaving the workspace
  untouched on rejection. Admission runs four steps in order:
  1. Rejects a non-map, invalid or future context, a proposal field outside
     `#{:tx/operations :tx/role}`, a `:tx/role` that differs from the
     context, or an operation count outside 1 to 128.
  2. Runs the payload stages over the whole transaction.
  3. Checks the declared basis against the real workspace.
  4. Validates each operation against the candidate (graph, semantic and
     guard stages), then commits the whole transaction once. The candidate is
     folded through the new `commit/stage`, which does not derive conflicts,
     so conflicts are derived once at the same transaction boundary that
     publication uses.
- `semantics` (new):
  - closed operation, creation and attribute fields;
  - statement cap;
  - creation and target types;
  - no created id inside the derived-conflict namespace (`conflict-`), so a
    proposal cannot occupy the id of a conflict the engine must derive;
  - reference integrity;
  - evidence source class, constraint kind, and claim kind (one of N14
    §2.1's four, when given);
  - goal closure needs an adopted decision covering every closed goal;
  - one adopted decision per choice point (unadopted alternatives coexist),
    and a single `accept-decision` may not name the same choice point twice;
  - the statement cap also covers revision and answer text.
- `validation`:
  - `concurrency-stages` is now `payload-stages` followed by `graph-stages`,
    with `basis-stages` exposed separately;
  - shared helpers `text?`, `created-specs` and `reject-operation`.
- `transaction`: new `responsibilities` set. `add-constraint` is open to every
  role, because semantic admission restricts `:hard` constraints by source
  (N14 §2.4, §5.3).
- `interface` (new): `new-object`, `admit`, `project`, `replay-transaction`.
- `commit`: new `stage`, which is `commit` without conflict derivation.
- Message catalog: 11 new `:anomaly/*` keys.

Deviations from the snapshot:

- Its single `semantics/check` was one `cond` with multi-form arms. Here the
  checks are named stages, each with its own anomaly subtype, so the
  scheduler can route on the reason.
- Attestation (hard-constraint sources, evidence receipts, experiment
  results, artifact exports) moves to a follow-up PR stacked on this one, so
  that each PR stays under the 600-line budget.
- `admit` returns an anomaly rather than `{:rejection ...}`.
- A proposal may no longer carry `:tx/activation` or `:tx/basis`; the
  snapshot accepted them and silently overwrote them.
- The user may add `:hard` constraints, as §2.4 allows.

## Testing Plan

New `admission_test` covers:

- impersonation, forged provenance fields, invalid and malformed context;
- reserved derived-conflict ids and claim kinds;
- closed fields and creation types;
- a duplicate choice point within one adoption;
- the statement cap on revisions;
- conflicts derived once at the transaction boundary;
- ordered validation with single-version publication and whole-transaction
  rejection;
- uncovered closure;
- the choice point and supersession, including that only the synthesizer
  supersedes a decision (enforced by the permission stage from #2330);
- challenge and evidence on adopted records;
- dangling references, distinct contributions versus retries, and basis
  staleness.

Ran locally on plain Clojure 1.12 with a local malli stand-in (Clojars is
unreachable from the authoring sandbox): all 12 deliberation-workspace suites,
166 tests, 542 assertions, 0 failures. clj-kondo 2026.10.26 (the CI version)
on the component: 0 errors. `poly test` and the Polylith check ran only in CI.

## Deployment Plan

None. Experimental component (N14 §0.4) with no production caller.

## Related Issues/PRs

- Base: the domain PR (#2330), which builds on the catalog PR (#2329).
  Contract: #2328.
- Next: run loop, scheduler and termination.

## Checklist

- [x] Every stage has a rejection test
- [x] Messages in the system catalog
- [x] No namespace exceeds three layers
