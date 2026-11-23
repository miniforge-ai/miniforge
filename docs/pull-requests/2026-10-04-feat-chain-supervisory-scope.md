<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: isolate chain runs and supervisory facts by scope

## Overview

Implement the approved N3 scope assignments for chain runs and supervisory facts.
This pure policy layer does not switch the live publisher or claim full payload validation.

## Motivation

Repeated chain invocations must not share sequence counters through a definition ID.
Workflow cross-references must not divert chain or intervention events into workflow scopes.

## Layer

Pure event-stream scope policy. Branched from main at `6b3c1336`.
Updated to main at `364c2131` after contract PR #2000 merged.

## Changes in Detail

- Resolve all nine chain lifecycle types by `:chain/run-id`.
- Register Spec snapshots and both intervention facts in supervisory entity scope.
- Support explicit inherited chain scope and reject unknown family members.
- Require current-write scope discriminators for chain and intervention facts.
- Separate family lookup vocabulary from scope resolution.
- Reject explicit markers that contradict fixed families, while preserving optional omissions.

## Testing Plan

Add negative and repeated-run regressions, verify them against the old policy,
then run focused tests and normal signed hooks serially.
Adversarial standards review and all exact-head CI/review gates precede merge.

The initial regressions produced 26 assertion failures on the original policy.
Profile regressions produced 22 failures before adding the discriminator guard.
Six adversarial regressions exposed contradictory markers on other fixed families.
The final focused suite passes 11 tests / 113 assertions, including absent workflow
cross-references and missing supervisory keys. All four deployed event-stream
consumers pass serially. Kondo reports zero warnings and errors.
The rebuilt CLI artifact passes the same 11 tests / 113 assertions from outside
the checkout, without source overlays. The changed-file standards scan is clean.
The whole-tree scan reports 12 pre-existing candidates outside this diff,
including an intentional bad-code string in the scanner's own example.
Scope and counter cases cover N3.EV.4–5; explicit current-write discrimination
covers N3.CP.9. Historical profile reading and migration are not implemented here.
The main merge exposed one prose-lint failure in an incoming PR document.
Its sentence is split without changing the recorded behavior or validation history.

## Deployment Plan

No producer migration or legacy journal rewrite. Payload validation and live
publication integration remain separate dependent changes.

## Related Issues/PRs

Follows contract PR #2000 and its prose prerequisite, PR #1999.

## Checklist

- [x] Prove regressions fail on the old policy.
- [x] Pass focused and deployed-consumer tests.
- [x] Complete adversarial standards review.
- [x] Pass normal signed hooks.
- Require clean exact-head review and all CI before merge.
