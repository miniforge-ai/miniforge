<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Ariadne changelog

Bump types are defined in [VERSIONING.md](VERSIONING.md). Entries above
PATCH state what an adopter has to do about the change.

## v1.6.1 — 2026-07-29 (PATCH)

Nothing to adopt. Worked examples only; no axiom, interface,
vocabulary, or section changed.

v1.6.0 scrubbed the spec's prose but not its diagrams, so the published
unit was internally inconsistent: the spec said `alice` and
`license:market-data` while five diagrams and one explainer
illustration still carried a real person's name and a named commercial
vendor. Fixed:

- Example principal is `alice` throughout the diagrams and the
  doorkeeper illustration (and its published HTML twin), matching the
  spec.
- The licensed-feed examples use the generic `license:market-data` and
  "licensed-feed pattern" in the diagrams, matching the spec. The named
  vendor stays with the product that holds that licence.
- Diagram 5's licence tuple drops a redundant `[TTL]` annotation that
  no longer fit the lane once the generic licensor id replaced the
  shorter vendor name. The lane is titled "license expiry /
  invalidation" and the panel states the TTL semantics twice more, so
  no information was lost.

## v1.6.0 — 2026-07-28 (MINOR)

First tagged and publicly published version. Ariadne moves here from
the private `thesium-career` repository, which is where it was
distilled; this repository is now its canonical home, because Miniforge
is an adopter and the architecture is written to be portable.

**No axiom, interface, or vocabulary changed.** The mechanics ratified
by [rfc-ariadne-adoption.md](../rfc-ariadne-adoption.md) and built in
adoption step 1 are the same mechanics. An implementation written
against the untagged v1.5 lineage needs no changes.

Changed:

- **§10 Instantiations emptied.** The section held a table of
  adopter-specific mappings (including systems that are not public and
  a proposal not yet made to the organization it named). Instantiation
  records now live with their adopters. The section number is retained
  as a placeholder — renumbering would break every citation, which
  VERSIONING.md classifies as a major change.
- **Licensed-feed examples genericized.** §12 and §13 used a named
  commercial market-data vendor as the worked example. The mechanism is
  unchanged; the licensor is now the generic `license:market-data`.
  The concrete instantiation stays private with the product that holds
  that licence.
- **Example principal renamed** from a real person to `alice`.
- **Added** `VERSIONING.md` — tag scheme, bump semantics, the
  compatibility surface a major bump protects, and the fork workflow.
- **Added** this changelog.
- **Version line added** to the spec header, pinning the tag.

## v1.0 – v1.5 — untagged lineage

Developed in `thesium-career` between 2026-07-26 and 2026-07-27, from
ADR 008 and its implementation review. Not tagged, not separately
published; the history is in that repository. Notable states:

- **v1.0** — initial distillation: axioms, four layers, the Zanzibar
  relation layer, ownership-by-provenance, offboarding and takeaway.
- **v1.2** — agent orchestration as principals; tools as a disclosure
  surface; elevation and scoped grants.
- **v1.4** — third-party rights: licensed feeds and open-web intake;
  App Store and DMG authentication posture.
- **v1.5** — semantic-convergence pass: clause sets replace scalar
  labels, authority-declared policy transforms, decision envelopes,
  transacted effects, the freshness contract, and scoped taint. This is
  the state Miniforge ratified and adopted in step 1.
