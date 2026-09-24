<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: issue scoped PR-creation grants

## Overview

Add runtime-owned issuance for `:effect/pr-create`, the next foundation for N7
governed PR emission. This change adds no provider mutation by itself.

## Motivation

The grant vocabulary declares PR creation, but the runtime issuer accepts only
merge and deploy requests. OPSV must obtain exact, bounded authority before
creating a PR through an effect transaction.

## Changes in Detail

- Add a closed PR-creation request with an active workflow, effect identity,
  allow/deny preflight, repository, base, branch, head commit and payload hash.
- Bind all target fields in the issued grant; allow one operation, no delegation,
  and a runtime-owned 15-minute expiry.
- Reuse the existing eligibility, scope, expiry and revocation machinery.

## Testing Plan

- Verify exact scope and runtime-owned constraints.
- Refuse missing/blank target fields, forged authority and denied preflight.
- Reject changed targets, expired/revoked grants and excess operation counts.
- Run execution-grant tests, pre-commit checks and an adversarial standards pass.

Validation: execution-grant passes 40 tests / 236 assertions in each of its three
consuming projects. Pre-commit passes Polylith, kondo (zero errors/warnings),
stratification, formatting, 347 smoke tests / 1,310 assertions and 8 compatibility
tests / 667 assertions. The standards pass traced schema admission, policy scope,
first-use empty history, expiry, revocation and exact-target authorization.

## Deployment Plan

No migrations or provider actions. Merge after settled review and passing CI.
The next application-layer PR consumes this issuance policy.

## Related Issues/PRs

`work/n07-opsv-governed-actuation.spec.edn`; N7 sections 5.4 and 7.2; N10
governed effects. Depends on the already-merged Ariadne runtime issuer.

## Checklist

- [x] Tests and standards review pass.
- [ ] CI passes and review comments are resolved.
