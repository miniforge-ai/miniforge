<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N10 governed tool execution spec completion (0.3.1 → 0.4.0-draft)

## Overview

Resolves a requirement satisfiable in neither direction, makes an unregistered
evidence type conditional on its owning spec, and adds Annex A.

N10 already carried 30 conformance requirement IDs, so this is a consistency
pass rather than a rebuild.

## Motivation

**§12.1 required and forbade the same thing.** It opened with "All governed
execution operations MUST emit events to the event stream (N3)" and immediately
followed with a note saying "Implementations MUST NOT emit them as unregistered
N3 event types". None of the fifteen types in its table is registered in
N3 §6 — verified for `capsule/*`, `postcondition/*`, `rollback/*`, and
`verification/*`. An implementation could satisfy neither reading.

**§12.2 specified an evidence type N6 does not define.**
`:evidence/type :governed-execution` is absent from N6 §3.1.1's artifact-type
list, so producing it conformantly is impossible.

## Changes in Detail

- **§12.1** reframed. The table is marked informative and retained as a record
  of intended observation points. The conformant path is stated: governed
  execution is observed through DecisionEnvelope, ExecutionGrant, and
  EffectTransaction identifiers on registered event types, and adding any row
  to the emitted surface is an N3 amendment first — registry entry, schema, and
  emission point together, per N3 §6.1.
- **§12.2** split into what is required now and what is proposed. Recording
  governed execution in the evidence bundle is a MUST, discharged today by the
  correlation set (`:effect-id`, `:grant-id`, `:envelope-id`) on evidence N6
  already defines. The richer `:governed-execution` artifact is marked not yet
  producible and retained as the proposed content of an N6 §3.1.1 amendment.
- **Redaction** pointed at N3 §8 as inherited by N6 §7.2, rather than left
  implicit in the table's "Capability (redacted)".

## Annex A (informative)

The finding worth surfacing: **§10's ten safety invariants have no enforcement
point.** No execution-capsule, crown-jewel, or postcondition component exists.
SI-8 (no execution beyond capability TTL), SI-9 (no credential persistence
beyond capsule lifetime), and SI-10 (revocation terminates execution within
5 seconds) are stated as guarantees and are currently assertions. SI-10 is the
one an operator is most likely to assume true.

`components/execution-grant` exists, so the identity §12.2 correlates on is
real.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- Verified none of the §12.1 event types appears in N3's §6 registry, and that
  `governed-execution` appears nowhere in N6.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Decide whether the governed-execution lifecycle warrants registered N3 event
   types; if so, amend N3 §3/§4.1/§6 together.
2. Register `:governed-execution` in N6 §3.1.1 if that evidence is wanted.
3. Implement an enforcement point for the §10 safety invariants — SI-10's
   revocation bound first, since it is time-bounded and testable.

## Related Issues/PRs

- Follows the N3/N4/N5/N6 completion passes; N2, N8, N9 in review
- Depends on: N3 §6 (event registry), N3 §6.1 (registry maintenance),
  N6 §3.1.1 (artifact types), N3 §8 (redaction)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Unsatisfiable requirement resolved
- [x] Cross-spec gaps gated on the owning spec rather than duplicated (020)
- [x] Annex A marked informative
- [x] No spec content extracted from implementation code (020)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
