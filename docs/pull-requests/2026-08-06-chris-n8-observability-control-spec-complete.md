<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N8 observability control interface spec completion (0.3.0 → 0.4.0-draft)

## Overview

Removes N8's parallel models for redaction, retention, and event schemas —
all three are owned by N3 — and adds conformance requirement IDs. Adds Annex A.

## Motivation

**Four vocabularies for two concerns.** N3 §8 defines redaction: what may never
be emitted, the `"[REDACTED]"` marker, truncation, and three field classes.
N3 §4.3 defines retention: four classes with minimums. N8 §5 carried its own
of each — privacy levels `metadata-only | redacted | full`, a
`:redaction/patterns` regex table, a `:redaction/field-rules` vocabulary of
`:include | :redact | :exclude`, and a `:retention/policies` schema with its
own day counts.

The practical consequence: an operator configuring redaction through N8 had no
way to know whether N3 §8.1's MUST NOT still applied. It does, unconditionally,
and it is not configurable — but nothing in N8 said so.

**A function in a config map.** §5.2 specified `:redaction/custom-fn function`.
`foundations/config-as-data` (dewey 007) is `alwaysApply` with hard-halt
enforcement. A function cannot be serialized, diffed, reviewed, or audited,
which defeats the purpose of a redaction policy an auditor needs to inspect.

**Stale duplicated schemas.** §10.1 reproduced N3 §3.15's `listener/*` and
`control-action/*` schemas with a fixed `:workflow/id`. Since N3 now streams
six scopes and those families take an inherited scope carrying `:scope/type`,
the reproduced copies were unusable on the five non-workflow scopes.

**No requirement IDs.**

## Changes in Detail

- **§5 rewritten.** Defers redaction to N3 §8 and retention to N3 §4.3, and
  names the withdrawn models explicitly so a reader meeting one in code knows
  it is a defect. §5.1 keeps what is genuinely listener-specific: which
  principal sees which field class, with `:restricted` suppressed per-recipient
  at delivery rather than per-event at emission.
- **§5.2** states redaction patterns are EDN configuration with a schema, and
  that deployment patterns apply *in addition to* N3 §8.1's set, never instead
  of it. `:redaction/custom-fn` withdrawn.
- **§5.3** adds one constraint N3 does not: control-action events are `:audit`
  class and MUST NOT be retained for less than the one-year floor. A control
  action is the record of a human overriding the machine.
- **§10.1** replaced by a reference table, with the inherited-scope property
  called out since it is what the reproduced schemas got wrong.
- **§12.4–§12.5** requirement IDs (`N8.CAP.*`, `N8.CTL.*`, `N8.PRV.*`) and six
  test obligations.
- **§13 MCI** no longer cites the withdrawn `metadata-only` privacy level.

## Annex A (informative)

- **Implemented** — listener capability enforcement and `include-payloads`
  filtering in `event-stream/listeners.clj`; control-action evidence in
  `evidence-bundle/collector.clj`.
- **Specified, not implemented** — no redaction configuration exists anywhere
  in the tree; the only match for "redaction" is a `progress-detector` test.
  This is the third spec in a row to record that gap (N3 Annex A, N6 Annex A),
  which is a signal about where implementation effort should go. Also missing:
  per-recipient `:restricted` suppression, and `:scope/type` on listener
  events.
- **Structural** — no retention floor for control-action events.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- Code blocks brace-balanced; no duplicate section numbers.
- Verified the withdrawn vocabularies survive only where their withdrawal is
  documented.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Implement redaction — now recorded as a gap by N3, N6, and N8.
2. Per-recipient `:restricted` suppression by RBAC role.
3. Emit `:scope/type` on listener and control-action events.
4. Enforce the audit retention floor for control actions.

## Related Issues/PRs

- Follows N3/N4/N5/N6 completion passes
- Depends on: N3 §8 (redaction), N3 §4.3 (retention), N3 §3.15 (event schemas),
  N3 §2.3 (scopes)
- Governed by: `standards/miniforge/foundations/specification-standards` (020),
  `standards/miniforge/foundations/config-as-data` (007)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Duplicated contracts replaced by references (020)
- [x] Config-as-data violation removed (007)
- [x] Annex A marked informative
- [x] No spec content extracted from implementation code (020)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
