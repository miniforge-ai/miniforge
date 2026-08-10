<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N1 architecture spec completion (0.7.0 → 0.8.0-draft)

## Overview

Aligns N1's Workflow entity with N2's canonical status vocabulary, adds
requirement IDs for the domain model and layering, and adds Annex A.

## Motivation

**N1 was a consumer the N2 sweep missed.** §2's Workflow entity declared
`:workflow/status` as `:pending, :running, :completed, :failed, :cancelled` —
the vocabulary N2 §2.2 superseded. It named `:pending` rather than `:queued`
and omitted `:paused` and `:blocked` entirely.

When I unified the vocabulary in N2 I swept N5 and N2-delta, and reported that
as the lesson from the N4 severity unification. N1 still slipped, which says
the sweep needs to be a search across all specs rather than a list of the
consumers I happened to think of.

**N1 had requirement IDs for everything except its own subject.** Six families
existed — `N1.AU.*`, `N1.CP.*`, `N1.EV.*`, `N1.RI.*`, `N1.RL.*`, `N1.SI.*` —
all added by later amendments for capabilities layered onto N1. The domain
model of §2 and the three-layer architecture and component boundaries of
§3–§4, which are what N1 is *for*, had none.

## Changes in Detail

- **§2** `:workflow/status` now defers to N2 §2.2's canonical vocabulary.
- **§8.4** adds `N1.DM.*` (5 requirements) and `N1.AR.*` (6 requirements).
  The ones worth naming: N1.DM.3 requires the status projection to stay within
  N2 §2.2's set with no synonym reachable; N1.AR.2 requires that no agent-layer
  component depend on a control-plane component, which Polylith does not check.
- **§8.5** adds six test obligations, three of which are static checks the
  repository can run continuously.

## Annex A (informative)

The useful split here is between architectural requirements that have a static
check and those that do not.

**Enforced:** interface-only access (`poly check` in pre-commit), stratum
direction (`bb lint:stratum`), component isolation (123 Polylith components).
These are the requirements that have not drifted — which is the point.

**Not enforced:** layer direction, because Polylith enforces *interface*
boundaries rather than *layer* direction, so a legal interface call can still
invert §3's layering. And status-vocabulary conformance, which is how
`:executing` — a value in no spec — reached both the implementation and N5's
documented CLI filter.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- No duplicate section numbers; 45 requirement IDs across eight families.
- Verified the other `:pending` occurrences in N1 belong to `:phase/status` and
  `:gate/status`, which are separate vocabularies N2 §2.2 does not govern.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Add a static check for layer direction (N1.AR.2) — the gap Polylith leaves.
2. Add a check that `:workflow/status` values stay within N2 §2.2's set
   (N1.DM.3), which would have caught `:executing`.
3. Apply boundary schema validation uniformly (N1.DM.2).

## Related Issues/PRs

- Follows the N2–N10 completion passes
- Depends on: N2 §2.2 (canonical status vocabulary)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Cross-spec vocabulary divergence resolved
- [x] Annex A marked informative
- [x] No spec content extracted from implementation code (020)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
