<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Enforce exact temporary OPSV experiment grant scopes

## Overview

Implement the grant-scope foundation of approved N7 §5.5 and N10 §1.5.
API surface class 1: internal EDN, trusted runtime composition.
Dependent branch from #2385's shared target contract, now merged.
Integrate current main before full validation and publish against main.

## Motivation

Generic grant containment permits additional proposal bindings. It cannot alone
prevent generic deployment authority from being reused for an OPSV trial.
Temporary trial and recovery effects need exact profile admission at the same
public authorization boundary used by decisions and durable effect commits.

## Changes in Detail

Add the dedicated load-test effect class and closed experiment scope data.
Reuse the shared Kubernetes target identity without copying its map contract.
Require complete, exact experiment bindings and operation/class correspondence.
Keep generic untagged grant behavior unchanged. Expose the typed internal schema
and route authorization through the profile guard before ordinary grant checks.
Keep existing deadline equality semantics and pin both the exact edge and the
first instant after expiry. Legacy commits refuse experiment effects whether
given unenforced markers or caller-supplied grant snapshots; only the current
authority path can execute them. Ordinary legacy-effect compatibility remains unchanged.

This foundation does not issue production experiment grants, enable a backend,
establish target ownership, execute load, or authorize live provider changes.
Runtime issuance, N4/N8 admission, provider execution, and verified recovery remain separate layers.

Dependency direction: effect-transaction → execution-grant interface → profile
boundary → shared containment/result factories and scope types → Kubernetes
target interface. No provider, phase, or workflow dependency enters the grant domain.

## Testing Plan

Use shared factories matching canonical runtime identities. Prove positive
trial/load/recovery shapes and fail closed for broad, stale, incomplete, changed,
or cross-class authority. Regress the ordinary grant path and durable commits.
Run strict kondo, true inferred strata, all affected product contexts, package
checks, targeted fault injection, and normal signed hooks in the shared slot.

Cheap source checks pass: 33 tests, 308 assertions; strict kondo has no warnings
or errors. All 12 implementation/test namespaces pass declared and truly inferred strata
(maximum three). Eight new unit tests contain 123 assertions and each takes
under 25 ms in the measured run. Eleven deliberate authority faults all cause
assertion failures with zero errors. Three published-build counterexample tests produce
eight assertion failures with zero errors, including revoked-grant snapshots;
no missing-API failures substitute for evidence. Full checks also cover the
public contract documentation and untagged legacy load-effect refusal.

## Deployment Plan

No default activation or provider effects. Require the dependency, all normal
review/CI gates, and owner merge before the next runtime layer.

## Related Issues/PRs

N7 completion; approved contract #2381 and shared target identity #2385.

## Checklist

- [x] Implement and adversarially review exact-profile authorization.
- [ ] Validate source, all affected products, and packaged commits.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
