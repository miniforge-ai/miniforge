<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Re-read retained preparation before candidate effects

## Overview

Require readable original preparation immediately before the candidate callback (N7 §5.5, §6.3).
API surface class 1: trusted-process EDN reads, not execution authority or experiment activation.

## Motivation

An acknowledged reference alone did not establish that the original artifact remained readable.
Removing it after publication still reached the candidate callback; the regression failed twice with zero errors.

## Changes in Detail

Read immutable publication without caching and require current evidence-assembly acknowledgement.
Check producer, workflow, bundle, attempt, candidate, pack, target, pin, digest and original-baseline invariants.
Reject final reports as original preparation; preserve protected-content validation and exception policy.
Read-only recovery consumers may use finalized assemblies; candidate execution still requires an active assembly.
Share the existing comparison-binding factory across native preparation, pin creation and retained reads.
This proves a read at the boundary, not continuing filesystem availability or authenticated storage ownership.
Protected host-controlled storage, current N4/N8/N10 authority and independent recovery remain required.

## Testing Plan

- Strict lint: zero warnings/errors; thirteen namespaces infer to at most three strata.
- Focused source regressions: 17 tests, 230 assertions; six safety faults fail assertions with zero errors.
- Both consuming product contexts, workspace composition and all task entrypoints pass.
- All 25 safe OPSV/workflow/N6 JVM suites: 128 tests, 1,449 assertions.
- Fresh isolated package, test paths only: 32 tests, 339 assertions; no production-source overlay.
- Native packaged compatibility: 308 tests, 1,903 assertions; all six faults detected again.

Correct the earlier provider proof wording: phase-opsv has two consuming product test contexts, not four.
The effect-transaction component separately runs in four contexts. No affected consumer was omitted.

## Deployment Plan

No live effects, new permission or activation. N7 runtime, load lifecycle and verified recovery remain incomplete.

## Related Issues/PRs

N7.VF.3–4, N7.EX.5–6; merged #2417 and #2418.

## Checklist

- [x] Trace actual publication → acknowledgement → retained read → candidate callback.
- [x] Complete adversarial standards, affected products, fresh package and fault checks.
- [ ] Settle exact-head full review, all CI and normal owner merge gates.
