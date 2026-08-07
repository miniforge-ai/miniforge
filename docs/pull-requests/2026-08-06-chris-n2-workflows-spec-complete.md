<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N2 workflow execution spec completion (0.5.0 → 0.6.0-draft)

## Overview

Unifies the workflow status vocabulary, makes terminality explicit, completes
the resume protocol, and adds conformance requirement IDs. Adds Annex A
recording implementation divergence.

## Motivation

**The workflow status vocabulary was spelled three different ways.**

| Source | Vocabulary |
|--------|------------|
| N2 §2.2 (the authority) | `:pending :running :completed :failed :cancelled` |
| N5-delta-supervisory §3.2 | `:queued :running :paused :blocked :completed :failed :cancelled` |
| N5 §2.3.2 CLI filter + implementation | `:executing` |

`:executing` is in neither spec. A CLI filter written against N5 matches
nothing produced against N2. `:paused` and `:blocked` were absent from the
authority that defines the lifecycle while N8 defined a pause control action
and the supervisory projection reported both — a state an operator can put a
workflow into that the lifecycle spec did not admit.

**Terminality was contradicted.** N5-delta-supervisory §3.2 says terminal
states MUST NOT return to active and a retry MUST produce a new run. N2 §8.1
listed "User cancelled workflow and wants to restart" as a resume case. Beyond
the direct contradiction, resuming a cancelled run would leave its evidence
bundle (N6) describing a run that later continued.

**The resume protocol was incomplete.** §8.2 said "verify the snapshot and
workflow definition are compatible" with no mechanism, while
N2-delta-phase-checkpoint-and-resume §9 and N3 §3.21 define the spec-hash
comparison and its event. §8 never said resume must preserve run identity.
§8.3 gated resume on "Too much time has passed (state may be stale)" — a
MUST NOT no implementation can apply consistently.

**No requirement IDs**, so nothing in N2 was traceable to a test.

## Changes in Detail

### Lifecycle vocabulary (§2.2, §2.3)

§2.2 is now the canonical set — `:queued :running :paused :blocked :completed
:failed :cancelled` — and explicitly names `:pending` and `:executing` as
withdrawn synonyms, so a reader hitting either in code knows it is a defect
rather than a variant. Every other surface is stated to project onto this set.

§2.3's transition diagram extended for `:paused` and `:blocked`, both
non-terminal, with the distinction stated: `:paused` is cleared by an operator,
`:blocked` by the blocking condition being satisfied.

### Terminality (§2.2, §8.1)

Terminal states never transition back to active. Re-running work that reached a
terminal state produces a new run with a new `:workflow/id`, which may seed
itself from the prior run's artifacts. §8.1's cancelled-restart case withdrawn.

### Resume protocol (§8.2–§8.4)

- Spec-hash comparison wired to `workflow/spec-hash-mismatch` (N3 §3.21) and
  N2-delta §9's disposition.
- `workflow/resumed` emission required, recording resumed-from state and phase
  and the phases skipped.
- Run identity preserved: same `:workflow/id`, no sequence reset, no re-emitted
  `workflow/started`.
- §8.4 replaces "too much time has passed" with three staleness conditions —
  configured resume window, non-resumable spec drift, unreachable external
  state. Refusal must name the condition and leave the run non-terminal rather
  than marking it failed on the operator's behalf.

### Events (§2.4)

Extended with the N3 §3.21 checkpoint and resume family, and a requirement that
pause and block transitions be observable on the stream.

### Conformance (§10.4–§10.5)

`N2.LC.*`, `N2.PH.*`, `N2.GT.*`, `N2.RS.*` plus seven test obligations.

### Annex A (informative)

- **Status vocabulary divergence** — the implementation emits `:executing`,
  which is in no spec's vocabulary, and `:pending`, now withdrawn. `:paused`
  and `:blocked` do appear in the tree, so the gap is naming rather than
  capability. N5 §2.3.2's documented CLI filter values need the same
  correction.
- **Specified, not implemented** — none of the six checkpoint/resume events is
  emitted anywhere, so §8's protocol is unobservable and a failed resume cannot
  be audited. No spec-hash comparison. No staleness check.
- **Structural** — nothing prevents a resume attempt against a terminal run.

### SPEC_INDEX

N2 entry updated; index bumped to 0.14.0-draft. This branch predates the N6 PR,
so its index still shows 0.12.0 as the prior entry; if N6 lands first the
version-history block will conflict and I will resolve it on rebase.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- Code blocks verified brace-balanced.
- No duplicate section numbers; top-level sections ascending 1–16.
- Internal `§N.N` references resolved; the one apparent miss is a cross-spec
  reference to N6 §2.13, verified by hand.
- Inbound `N2 §x.y` references enumerated before editing — they reach §1.1, §5,
  §6.4, §6.5, §9.1, §13.x, and §14. No section carrying an inbound reference
  was renumbered; §10.4–§10.5 are additive and Annex A appends.

§10.5's test obligations describe tests that do not exist yet — follow-on work.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

Tracked by Annex A. The vocabulary rename is the one with the widest blast
radius and should go first, since every consumer filters on these values.

1. Rename `:executing` → `:running` and `:pending` → `:queued` across the
   implementation, and correct N5 §2.3.2's documented filter values.
2. Emit the six checkpoint/resume events so §8's protocol is observable.
3. Implement spec-hash comparison on resume.
4. Implement the §8.4 staleness check and its refusal reporting.
5. Enforce terminality — refuse resume against a terminal run.

## Related Issues/PRs

- Follows: [#1641](https://github.com/miniforge-ai/miniforge/pull/1641) (N3),
  [#1658](https://github.com/miniforge-ai/miniforge/pull/1658) (N4),
  [#1668](https://github.com/miniforge-ai/miniforge/pull/1668) (N5),
  [#1684](https://github.com/miniforge-ai/miniforge/pull/1684) (N6)
- Depends on: N3 §3.21 (checkpoint/resume events),
  N2-delta-phase-checkpoint-and-resume §9,
  N5-delta-supervisory-control-plane §3.2
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Cross-spec vocabulary divergence identified and resolved in the authority
- [x] Internal contradictions identified and fixed
- [x] New sections use RFC 2119 keywords (020)
- [x] Annex A marked informative — carries no new requirements
- [x] No spec content extracted from implementation code (020 critical rule)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] Code blocks brace-balanced; no duplicate section numbers
- [x] Inbound references checked before editing
- [x] SPEC_INDEX updated (020: index is authoritative)
- [x] PR doc created (721)
