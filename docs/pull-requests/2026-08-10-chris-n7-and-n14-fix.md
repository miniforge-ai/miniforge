<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N7 completion, and a §11 collision from the N12–N15 pass

## Overview

Completes N7, which the sweep missed, and fixes a duplicate section number
introduced by the previous PR.

## Motivation

**N7 was surveyed and then never scheduled.** It appeared in the original
survey with 0 requirement IDs, and every subsequent wave plan omitted it. The
final verification across all 22 normative specs surfaced it as the only
established spec still carrying no requirement IDs and no annex. This PR closes
that.

**N14 gained a duplicate §11.** The N12–N15 pass appended a
`## 11. Conformance Requirements` to N14, which already had
`## 11. Conformance staging` at line 393. The appended section landed after
§13, so the file read 10, 11, 12, 13, 11. My verification script caught it after
merge — it should have caught it before, and would have if I had run the
duplicate check per-file after editing rather than only on the final set.

## Changes in Detail

### N7

- `N7.EX.*` (4), `N7.VF.*` (2), `N7.AC.*` (4) requirement IDs with four test
  obligations, placed as §8.5 so the MCI section keeps its number.
- Annex A.

### N14

- `## 11. Conformance Requirements` → `## 14.`, with its subsection. Top-level
  sections now run 0–14 without repetition.

## What N7 turns out to be

N7 is the **best-served spec in the set** on the dimension that defeated every
other extension spec reviewed in this sweep: its event family is both
registered and emitted. The nine `opsv.*` types are in N3 §3.14's registry, and
`event-stream/opsv.clj` emits them with tests in `phase-opsv` and
`event-stream`. N8, N9, N10, and N14 each declared event types that were never
registered, so none of them can be emitted conformantly.

`components/opsv` implements risk, convergence, actuation, verification, and
schema, with a simulated adapter alongside.

Its gap is elsewhere and is a dependency rather than an omission: §7.3 requires
apply actions to execute as N10-governed effects with verified rollback and
postcondition monitoring, and N10 Annex A records that no postcondition
component exists. N7's strongest safety requirement rests on machinery that is
not there.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all four changed files.
- No duplicate section numbers in either spec; N14's top-level sections verified
  contiguous 0–14.
- Verified N7's registered event types are emitted before claiming so.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. N7.AC.2 depends on N10's postcondition monitoring, which does not exist —
   that dependency should be tracked against N10's implementation rather than
   N7's.
2. `opsv.drift/detected` is registered in N3 with no producer.

## Related Issues/PRs

- Completes the normative spec set; follows the N1–N11, delta, and N12–N15 passes
- Fixes a collision introduced by [#1746](https://github.com/miniforge-ai/miniforge/pull/1746)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Gap in my own sweep identified and closed
- [x] Duplicate section number from the prior PR fixed
- [x] Annex A marked informative
- [x] No spec content extracted from implementation code (020)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
