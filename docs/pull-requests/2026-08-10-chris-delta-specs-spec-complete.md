<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: delta-spec completion pass (all seven deltas)

## Overview

Normalizes metadata across the seven delta specs, adds conformance requirement
IDs to the six that carry requirements, fixes a duplicate section number, and
records that one delta states no requirements at all.

Grouped into one PR because the defects are systemic rather than per-spec —
each delta exhibits the same few, and fixing them one at a time would produce
seven near-identical reviews.

## Motivation

**Metadata was carried three different ways.**

| Convention | Specs |
|---|---|
| Header block, as N1–N15 use | N11-delta |
| Bulleted list under the H1 | N5-delta-2, -3, -4, -supervisory |
| A `## Spec metadata` section | N2-delta, N4-delta |

Three of the bulleted ones also wrapped the version in backticks, so a reader
or a script looking for the version had four shapes to match. **N4-delta
carried no version anywhere.**

I initially mis-surveyed this as "six deltas have no version header" — the
regex matched only the header form and missed the bulleted one. The finding is
inconsistency, not absence, and the PR is scoped to that.

**No delta had conformance requirement IDs.** Every core spec now does, so a
conformance suite can cite a delta's requirements only by quoting prose.

**N5-delta-3 had two sections numbered §3.6** — the pack-management producer
and a "Shared contract" section. Both inbound `§3.6` references (from §5 and
the acceptance checklist) mean the producer, so the Shared contract section was
the misnumbered one.

**N4-delta contains no RFC 2119 keyword at all** — not one MUST, SHALL,
SHOULD, or MAY — while sitting in `specs/normative/`. Standard 020 requires
normative specs to use them. An implementation cannot conform to a document
that states no requirements.

## Changes in Detail

- **Metadata normalized** to the header form on all seven, preserving Spec ID,
  Amends, and Related where they existed. N4-delta gained a version.
- **Requirement IDs** on the six deltas with MUSTs: `N2D.CK.*` (6),
  `N5D1.SV.*` (7), `N5D2.SC.*` (4), `N5D3.OE.*` (5), `N5D4.AE.*` (4),
  `N11D.RA.*` (3), each with test obligations. The ones worth naming are the
  invariants that keep the supervisory model coherent: `N5D1.SV.3` (sole
  emitter per entity family), `N5D1.SV.7` (terminal states never reactivate),
  and `N5D4.AE.3` (the correlator must filter its own output out of its input,
  or it feeds itself).
- **N5-delta-3 §3.6 → §3.7** for the Shared contract section, with
  N5-delta-4's cross-reference updated to match.
- **Version histories** added to all seven.

### N4-delta: recorded, not invented

Its content is a design description — a candidate model, provenance fields, an
approval lifecycle, compilation guarantees — written declaratively. Converting
that prose into MUSTs is a deliberate authoring act with real consequences for
implementers, so this PR does not do it. Inventing requirements on someone
else's design would be worse than recording the gap.

A Status subsection states that the spec is effectively informative until its
requirements are stated, and names the two open dispositions: state the
requirements and keep it in `normative/`, or reclassify it to `informative/`
and let N4 carry whatever is binding. SPEC_INDEX is authoritative on scope
(020) and should record whichever is chosen.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all eight changed files. Two pre-existing MD040
  fenced-block warnings in N5-delta-4 were initially left alone as
  out-of-scope, but staging the file made them blocking — the pre-commit
  hook's markdown formatter exits non-zero when a staged file carries an
  unfixable warning, which failed the commit twice. Both fences are now
  labelled `text`.
- N5-delta-4's cross-reference to N5-δ3's shared contract updated to §3.7
  after the renumbering. I had checked inbound references *within*
  N5-delta-3 and not across the other deltas; review caught it.
- No duplicate section numbers remain across any delta.
- Verified both inbound `§3.6` references before renumbering.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Decide N4-delta's disposition — state its requirements or reclassify it.
2. The `N5D1.SV.*` requirements describe invariants the supervisory-state
   component is meant to hold; nothing tests them today.

## Related Issues/PRs

- Completes the delta specs after the N1–N11 core passes
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Specs reviewed against current state before editing
- [x] Initial mis-survey corrected before scoping the change
- [x] Duplicate section number resolved; inbound references checked first
- [x] No requirements invented for N4-delta — gap recorded instead
- [x] Copyright headers present (810)
- [x] `markdownlint` clean apart from documented pre-existing warnings
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
