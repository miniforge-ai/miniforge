<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Issue exact temporary OPSV experiment grants

## Overview

Domain/application grant issuance, API surface class 1, internal EDN.
Dependent branch from #2392's exact authority foundation. Merge #2392 first,
then integrate current main before final validation and publication.

## Motivation

The production issuer must derive experiment authority from a runtime-attested
request, not inherit generic deployment policy or accept caller-selected ceilings.
Keep the existing issuer and eligibility checks; do not introduce a parallel broker.

## Changes in Detail

Extend the closed canonical request and explicit runtime policy profile for
temporary staging effects. Preserve generic request compatibility and derive
root, nondelegable, one-effect authority with bounded expiry and exact bindings.
Decompose the existing nested issuance boundary while retaining its error contract.
Share operation, resource-identity and load-bound policy between issuance and authorization.
Derive expiry from the declared duration, capped by the existing 30-minute runtime policy.
Typed semantic refusals and denied preflight return unauthorized; malformed requests return invalid-input.

This does not establish staging/ownership, implement N4/N8 admission, enable a
provider, or authorize live effects. Runtime composition must obtain real
preflight and commit-time fencing before any future provider use.

## Testing Plan

Pin valid trial/load/recovery issuance and fail closed for incomplete, denied,
ineligible, cross-class, or caller-expanded authority. Regress all existing
issuer requests, durable current-authority composition, and packaged behavior.
Use strict lint, true inferred strata, all affected product contexts, targeted
faults, and serialized normal signed hooks; retain all PR review/CI gates.

Cheap source checks pass 40 tests/492 assertions, including real breach-history,
independent recovery, durable commit composition, and existing issuer compatibility.
Strict kondo is clean. All 13 changed namespaces pass true inferred strata, at most three.
Fourteen deliberate authority faults cause assertion failures with zero errors.
The published issuer rejects all five valid operation requests: five assertion failures, zero errors.
Seven pure unit tests retain 93 assertions; the slowest measured run takes 1.131 milliseconds.
Compile the validator once, matching production, rather than rebuilding it per assertion.
The existing constructor instrumentation now targets the canonical core factory after decomposition.

## Deployment Plan

No default activation or live effects. Require #2392 and all normal owner merge gates.

## Related Issues/PRs

N7 completion; approved authority amendment #2381 and scope foundation #2392.

## Checklist

- [ ] Implement and adversarially review the existing-issuer extension.
- [ ] Validate all affected products, package, faults, and compatibility.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
