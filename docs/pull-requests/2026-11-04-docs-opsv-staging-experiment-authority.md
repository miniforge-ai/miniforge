<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Separate temporary staging experiments from final actuation

## Overview

Define the approved N7/N10 permission boundary for temporary staging experiments.
Base: current main after #2380. No implementation dependency or runtime change.

## Motivation

Verification needs candidate and load effects before it can establish success.
Final apply still requires successful verification. Separate experiment authority
resolves that ordering without granting mutation through an actuation mode.

## Approval Record

The product owner approved separate experiment authority on 2026-11-04.
Execution defaults off; recommend-only alone stays read-only. Trial/load effects
require exact attempt/target/payload grants, dedicated load-test scope, separately
authorized cleanup, and verified original-baseline recovery.
Final PR/permanent-apply gates stay unchanged.
This approves the contract and implementation, not live experiments or cluster changes.

## Changes in Detail

Amend N7 and the adopted N10 Ariadne profile; align N1, N8 safe-mode, and the
specification index. Safe-mode fences forward effects while admitting only
separately authorized compensation; general mutation circuits stay blocked.
Reuse registered N3 events and N6 artifacts.
No parallel broker, extra actuation mode or new event/evidence wire contract.

## Testing Plan

Check cross-spec consistency, exact authority separation, refusal paths,
emergency stop, recovery, and compatibility. Run normal prose/Markdown and
commit gates. Require current-head CI and settled full review before merge.
Documentation only: no production code or executable test behavior changes.

## Deployment Plan

Contract only. Runtime enforcement follows in independently tested PRs.
No backend activation, live experiments, provider writes or credential changes.

## Related Issues/PRs

N7 completion; #2373 native preparation, #2374 discovery and #2380 measurements.

## Checklist

- [ ] Amend and adversarially review the approved contract.
- [ ] Validate documentation and normal commit gates.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
