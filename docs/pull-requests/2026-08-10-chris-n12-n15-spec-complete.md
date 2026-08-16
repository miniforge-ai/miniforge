<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N12–N15 completion pass

## Overview

Adds conformance requirement IDs, test obligations, and implementation annexes
to the four youngest normative specs, and gates N14's event and evidence
declarations on the specs that own them.

Grouped into one PR because all four share the same starting state — 0.1.0-draft,
no requirement IDs, nothing implemented — and the same treatment.

## Motivation

**None of the four had requirement IDs.** N12 and N13 annotate conformance at
the section level ("Conformance: MUST"), which is better than nothing but not
citable by a test.

**N14 §9.1 declared ten required event types, none registered in N3.**
`workspace/opened`, `workspace/transaction-committed`, and eight others are
listed as "Required event types (N3 envelope)". Under N3 §6 an implementation
MUST NOT emit an unregistered `:event/type`, so the list could not be satisfied.
This is the fourth spec to carry that pattern — N8, N9, and N10 each had a
version of it.

It matters more here than it did there: §9.1 also makes the event stream the
workspace log, with the graph derived and reconstructible from it. The spec's
central mechanism is the part that is blocked.

**N14 §9.2's four N6 exports** — decision record, transaction log, graph
snapshot, per-run accounting — are likewise absent from N6 §3.1.1.

## Changes in Detail

- **Requirement IDs and test obligations** on all four: `N12.CE.*` (7),
  `N13.PI.*` (6), `N14.WS.*` (6), `N15.CH.*` (6).
- **N14 §9.1 and §9.2** gated on N3 §6.1 and N6 §3.1.1 registration
  respectively, with the lists retained as the proposed content of those
  amendments.
- **Annex A** on each.
- Versions bumped 0.1.0 → 0.2.0-draft with histories.

### Why N14's types were not simply added to N3

N3's registry is the contract every consumer reads. Adding ten types for a spec
that is explicitly speculative (§0.4) and has no implementation would
misrepresent the stream's surface — a consumer reading the registry would see
event types nothing will ever emit. The list stays with the spec that proposes
it until that spec survives its gate.

## Annex A — the finding worth surfacing

**N15 is the one spec whose absence blocks another's disposition.** Its §8 gate
G0 decides whether N14 is kept or demoted to informative. Until the harness
exists, that gate cannot run, so N14 stays speculative indefinitely. Of the four,
N15 is the one with a downstream consequence for the spec set.

The others: no `context-economy` component (N12) — though `components/context-pack`
and the MCP context server are the natural host. No violation ledger or
cross-repo promotion (N13), which means §9's "gate as safety net" framing
describes the current state by default rather than by design, since the
teaching path it backs up does not exist. No deliberation workspace (N14);
`components/workspace` is unrelated, handling workflow paths.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all six changed files.
- Verified none of N14's ten event types appears in N3's registry, and none of
  its four export types appears in N6.
- Verified `components/workspace` is unrelated to N14's workspace model before
  saying so.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Build the N15 harness — it unblocks N14's gate decision.
2. Land N12's ladder in `components/context-pack` rather than a new component.
3. N13's violation ledger, which is what turns the gate from the only mechanism
   into the safety net it is described as.

## Related Issues/PRs

- Completes the normative spec set after the N1–N11 and delta passes
- Depends on: N3 §6 (event registry), N3 §6.1, N6 §3.1.1 (artifact types)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Specs reviewed against current state before editing
- [x] Unregistered event and evidence types gated on their owning specs (020)
- [x] No types added to N3's registry for unimplemented, speculative specs
- [x] Annexes marked informative
- [x] Copyright headers present (810)
- [x] `markdownlint` clean
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
