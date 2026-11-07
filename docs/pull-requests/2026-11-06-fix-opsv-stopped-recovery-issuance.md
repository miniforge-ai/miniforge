<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Admit narrowly scoped OPSV recovery after workflow stop

## Overview

Keep forward issuance running-only while admitting exact OPSV compensation for
paused, blocked, failed or cancelled runs. Base branch: main. Layer: authority boundary.
Depends on the merged N7/N10 staging authority contract and #2440 recovery timing.

## Motivation

N8 safe-mode pauses workflows and revokes forward grants.
The shared issuer currently requires running status for every operation, including recovery.
That prevents fresh independent compensation authority when it is most needed.
Changing a stopped workflow back to running would violate the lifecycle contract.

## Changes in Detail

Specialize only the OPSV request boundary for stop-load, cleanup-load and
restore-baseline operations. Preserve exact scopes, an allowing preflight, independent
grant identity, lifetime, non-delegation, breach eligibility and single-effect limits.
Queued or completed workflows remain ineligible; recovery precedes successful completion.
All generic profiles and both forward OPSV operations remain running-only.
No workflow status transition, provider execution or automatic authorization.

## Testing Plan

Pin the stopped-status/operation admission matrix and generic-profile refusals.
Reproduce the real public issuer refusal before the fix.
Exercise independent registered recovery and durable commit after forward revocation.
Keep refusal, grant expiry, payload scope and count checks active.
Run strict lint, inferred strata, consuming-product tests and isolated native packaging.
Serialize all local JVM, build and hook work with the shared slot.

Validation passed: strict lint with zero warnings/errors and seven genuinely
inferred namespaces within three strata. The original source reproduced 24
assertion failures with no errors. The corrected contract suite passed 49 tests
and 721 assertions; all four consuming products passed grant/effect tests.
Real-store JVM integration passed 18 tests/310 assertions, and broader OPSV
integration passed 147 tests/1,678 assertions.

A fresh CLI build passed all task loads. Isolated Babashka package checks passed
the same 49 contract tests/721 assertions and native compatibility's 308 tests/1,903 assertions.
No production source overlay. JVM-only SQL temporal tests passed in all four
JVM products; they are not loaded into Babashka. All 16 injected faults triggered
assertion failures with zero errors, including reinstated running-only recovery
and unfenced stopped-forward issuance. No live provider calls.

The duplicate-commit regression checks the existing conflict response, unchanged
durable success record, and one provider call. It does not require success replay.

## Deployment Plan

No activation or live effects. Runtime orchestration still needs an independently
approved retained plan, current decisions, exact grants, fresh recovery preconditions
and verified postconditions. This issuer change supplies no plan or admission itself.

## Related Issues/PRs

N7 §5.5, N8 §3.4.2, N10 §1.5; #2407 experiment issuance; #2440 recovery timing.

## Checklist

- [x] Prove the regression, implement and adversarially review the actual diff.
- [ ] Pass strict validation, normal signing and hooks without exceptions.
- [ ] Settle current-head and fresh full review, CI and owner merge.
