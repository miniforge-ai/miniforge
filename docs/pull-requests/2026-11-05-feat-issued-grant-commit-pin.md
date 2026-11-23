<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Share runtime-issued grant identity pinning at durable commit

## Overview

Extend the shared current-commit boundary with an independently retained issuance identity.
Migrate the existing governed PR consumer so native experiments can reuse the same protection.
This introduces no authority, provider effects, experiment activation or completed N7 runtime composition.

## Motivation

Two valid grants can authorize identical scope without being the same issuance.
A durable record must not silently switch from the runtime-issued grant to another one.
The existing PR coordinator implements this protection privately; share it rather than copy it.

## Changes in Detail

The existing five-argument current-commit API retains its behavior.
The new six-argument form adds the runtime-retained issued grant UUID before the lookup port.
Check that pin against the actual recorded identity inside the shared grant lookup.
Refuse a mismatch before reading authority or time, claiming the record or invoking the provider.
Do not perform a separate preliminary proposal read that can race the actual commit read.

Keep current-authority lookup, fresh time, authorization, claim comparison and durable result handling unchanged.
Keep PR-specific scope completeness checks in their original domain component.
Move the generic identity refusal message into the effect layer and remove the obsolete PR-only copy.
The public effect interface becomes a thin alias with compiled ingress validators behind it.
No source dependency, profile policy or grant issuance behavior changes.

## Testing Plan

Use real public grant issuance, identical canonical scopes and durable proposals.
Cover independent issuance substitution, malformed pins, lookup order, revocation,
expiry, unavailable authority, backend identity mismatch, duplicate execution and a real claim race.
Retain the existing legacy current-commit and governed PR regressions unchanged.

Source validation passes:

- Strict lint across both complete components: zero warnings or errors.
- Seven changed namespaces infer to at most three strata, with zero findings.
- New issuance-pin plus existing commit/PR regressions: 34 tests, 231 assertions.
- Bypassing the pin causes six assertion failures with zero errors.
- Reading authority before checking the pin causes one assertion failure with zero errors.

Two initial assertions incorrectly forbade the existing fresh-clock read before later conflicts.
They now pin the correct existing sequence; product ordering was not changed to satisfy them.

Full validation passes:

- Workspace composition and effect-transaction tests in all four product contexts.
- Opsv-actuation tests in both consuming product contexts.
- All 24 safe OPSV/workflow/N6 JVM integration suites: 113 tests, 1,340 assertions.
- Every task entrypoint loads, and a fresh CLI artifact builds.
- Isolated Babashka artifact, test paths only: 34 tests, 231 assertions.
- Native packaged compatibility regressions: 308 tests, 1,903 assertions.
- Both deliberate pin faults are detected with assertion failures and zero errors.

No production-source overlay, live provider effect, signing exception or hook bypass is used.

## Deployment Plan

No live effects, activation or new permission. Preserve every normal review, CI and owner merge gate.

## Related Issues/PRs

N7/N10 implementation; shared dispatch #2416 and native provider #2417.

## Checklist

- [x] Implement shared issuance pinning and migrate the real PR consumer.
- [x] Trace the whole source diff against Miniforge standards and deliberate faults.
- [x] Complete affected products and packaged validation.
- [ ] Settle full current-head review, CI and owner merge gates.
