<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N9 external PR integration spec completion (0.2.0 → 0.3.0-draft)

## Overview

Defers N9's scope and event contracts to N3, removes a deprecation-cycle
requirement that contradicts the pre-release stance, corrects an undefined
binary name, and adds conformance requirement IDs.

## Motivation

**§7 restated contracts N3 owns, at an older version.** §7.1 explained the
`:workflow/id` nil rule as a PR-only carve-out — the shape N3 §2.3 had before
it generalized to six scopes. §7.2 reproduced N3 §3.16's six event schemas.
Both were stale copies that drift silently.

**§14 mandated a deprecation cycle.** "Breaking changes MUST increment a major
version and MUST be supported in parallel for at least one deprecation cycle."
N3 §7.4 states the opposite: because the product is pre-release, dual-emission
is not required and implementations cut over. As written, N9 obliged
implementations to carry compatibility machinery no consumer needs.

**An undefined binary.** The MCI and CLI sections invoked `mf fleet prs` in
five places. N5 §2.1 defines the command as `miniforge <namespace> <command>`
and defines no `mf` alias anywhere.

**No requirement IDs.**

## Changes in Detail

- **§7.1** references N3 §2.3 and states only what is N9-specific: a
  Miniforge-originated PR's lifecycle events are Workflow-scoped and carry
  `:pr/id` as a cross-reference; an external PR's events are PR-Work-Item
  scoped with a nil `:workflow/id`; and per N3 §5.1 delivery is strictly by
  scope, so a consumer wanting both subscribes to both.
- **§7.2** replaced by a reference table naming the six event types, their
  purpose, scope, and retention class.
- **§14** aligned with N3 §7, with the withdrawn parallel-support requirement
  named so a reader meeting it in an older copy knows it is gone.
- **`mf` → `miniforge`** in all five places.
- **§17–§18** requirement IDs (`N9.WI.*`, `N9.EV.*`, `N9.AT.*`, `N9.AS.*`,
  `N9.EB.*`) and seven test obligations. The tier requirements are the ones
  worth having IDs for — N9.AT.2 (no heuristic auto-escalation) and N9.AT.3
  (no approve/merge below Tier 3) are the constraints that keep an automation
  tier meaningful.

## Annex A (informative)

- **Specified, not implemented** — none of N9's six event types is emitted, so
  external PR state is not observable on the stream and the `:pr/id` scope of
  N3 §2.3 has no producer.
- **Structural** — `pr-lifecycle` runs a separate in-process bus, so the PR
  events that do exist are neither sequenced nor replayable per N3 §2.2.
  Secret redaction depends on N3 §8, which has no implementation — the fourth
  spec to record that gap.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- Code blocks brace-balanced; no duplicate section numbers.
- Verified zero remaining `mf` invocations.
- Heading levels corrected after the appended sections initially skipped h3.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

1. Emit the six N9 event types so external PR state is observable.
2. Move `pr-lifecycle` onto the N3 stream so its events are sequenced and
   replayable.
3. Implement redaction — now recorded by N3, N6, N8, and N9.

## Related Issues/PRs

- Follows the N3/N4/N5/N6 completion passes and N2/N8 in review
- Depends on: N3 §2.3 (scopes), N3 §3.16 (event schemas), N3 §7 (versioning),
  N3 §5.1 (delivery by scope)
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Duplicated contracts replaced by references (020)
- [x] Backward-compat requirement removed — product is pre-release
- [x] Annex A marked informative
- [x] No spec content extracted from implementation code (020)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] SPEC_INDEX updated
- [x] PR doc created (721)
