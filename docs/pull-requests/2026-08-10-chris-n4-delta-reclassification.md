<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: reclassify N4-delta to informative, fold its novel contracts into N4

## Overview

Moves the policy-compilation delta from `normative/` to `informative/`, folds
the two ideas in it that N4 lacked, and records a larger gap the investigation
surfaced.

## Motivation

**The document bound nothing.** Its requirements are written entirely
lowercase — 25 `must`, 4 `should` — with **no uppercase RFC 2119 keyword
anywhere**. Per RFC 8174 only the uppercase forms carry normative weight, so a
document indexed as a normative amendment to N4 formally required nothing.

**Its subject does not exist.** N4-delta specifies a document-to-candidate
compiler with a candidate lifecycle, producer types, and promotion rules.
Nothing in the tree implements it — no candidate keys, no compiler component.

**What does exist is better.** `components/policy-calibration` decides
gate-readiness *empirically*: it measures a semantic judge's false-positive and
recall rates over a seeded corpus across independent runs, and
`gate_check.clj` refuses to ship a rule that gates via the judge without a
passing `:gate-ready?` record. That answers the same question as N4-delta's
declared `enforceability` field, with evidence instead of assertion.

## Changes in Detail

### Reclassification

`specs/normative/N4-delta-policy-compilation-contract.md` →
`specs/informative/I-policy-compilation-contract.md`, with its Status section
rewritten to say why. Removed from the index's amendments table and
applicability table; added to the informative listing.

### Folded into N4

Both optional, both defaulting to current behaviour, so no existing rule is
invalidated.

- **`:rule/provenance` (§2.3.3)** — source-type, source-ref, support-type,
  source-fingerprint, plus optional locator, excerpt-hash, and compiler
  identity. OPTIONAL for hand-authored rules, REQUIRED for derived ones. Two
  rules govern it: a derived rule retains provenance from every materially
  contributing source rather than collapsing to one pointer, and provenance
  survives promotion. This is the rule-level counterpart to N6 §2.13 — pack
  hash tells a reader which bytes ran, provenance tells them why those bytes
  say what they say.
- **`:rule/enforceability` (§2.3.2)** — `:executable` / `:heuristic` /
  `:advisory` / `:human-review`, defaulting to `:executable`, with the rule
  that a rule MUST NOT be silently promoted to `:executable`. Silent promotion
  turns guidance into a blocker for work that was never meant to be gated, and
  the operator sees only the block. Severity and enforceability are orthogonal:
  a `:critical` `:advisory` rule says "this matters and a human decides", not
  "this blocks".

§2.3.2 defers to measurement where both exist: a rule declaring `:executable`
that fails calibration does not gate.

### Recorded, not specified — N4 Annex A.5

The investigation surfaced something bigger than the fold. **N4 mentions
calibration zero times**, yet the shipped rule model carries
`:rule/enforcement {:action :hard-halt | :require-approval}`, a
semantic-vs-deterministic detector distinction, and a gate that refuses to ship
a semantic gating rule without an evidence-backed reliability record.

That is one of the stronger safety properties in the system and it exists only
in code. Annex A.5 records it. Writing the contract belongs in a deliberate N4
amendment — reverse-engineering it from the implementation is what standard 020
forbids.

## Two corrections to earlier claims

Both were mine, both from unverified assumptions in a command:

1. I reported that **nine specs were missing from SPEC_INDEX**. They are not.
   All seven deltas are in an `### Indexed normative amendments` table and N11
   is indexed too. I ran the check in the main checkout, which sits on an
   unrelated branch and never picked up the merges, so I read a stale file.
2. I reported that N4-delta **"contains no RFC 2119 keyword… states no
   requirements."** The first half was right — the original file has zero
   uppercase keywords. The second was wrong: it states about twenty-five
   requirements in lowercase, which my case-sensitive grep missed.

   I then over-corrected, telling the maintainer the document "contains one
   uppercase MUST and one SHALL". That came from re-measuring the file *after*
   I had added a Status section quoting those words — I was counting my own
   text. Verified against the pre-edit blob this time: zero. The 0.22.0 index
   entry is corrected in place rather than left standing.

   The root cause of all three passes at this: measuring a file I had already
   modified, without checking the original.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all four changed files.
- No duplicate section numbers in N4 — an A.5 collision was caught and
  renumbered during the edit.
- Verified `components/policy-calibration` and `gate_check.clj` behave as
  described before writing Annex A.5.
- Verified no `candidate/*` keys exist in the tree before claiming the compiler
  is unimplemented.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Specify the enforcement/detector/calibration model in N4 — Annex A.5's gap.
   This is the one with a real safety property behind it.
2. If the pack compiler is ever built, it is Miniforge-product scope (like
   N7–N10), not MiniForge Core: nothing outside miniforge originates packs.

## Related Issues/PRs

- Follows the full spec-completion sweep (N1–N15, deltas)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Two earlier incorrect claims identified and corrected
- [x] Novel contracts folded forward; the rest moved with the document
- [x] Optional defaults so no existing rule is invalidated
- [x] Calibration gap recorded, not reverse-engineered (020)
- [x] Index amendments and applicability tables updated
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] PR doc created (721)
