<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Prepare bounded native replica trial and restoration requests

## Overview

Native request construction, based on main and the approved N7/N10 temporary staging contract.
This is a provider-request layer, not runtime admission or a live execution adapter.

## Motivation

Replica trials and baseline restoration need canonical payloads whose identity
and preconditions can be bound by exact grants and durable effect proposals.
Name-only writes must not affect a replacement Deployment.

## Changes in Detail

Prepare only Deployment replica changes with UID and resource-version tests.
Trial replicas must stay within the predeclared candidate population bounds.
Restoration uses the original baseline's desired count, not a newly observed expectation.
Fresh current counts must match a declared candidate or recovery population.
Both original-to-desired and current-to-desired deltas must fit the pack's replica limit.
No arbitrary patch, command, URL, HPA/KEDA configuration or provider execution is introduced.
Runtime N4/N8/current grants, retained original material and verified recovery remain required.

Use conditional [Kubernetes JSON Patch](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources)
with ordered tests before the replica replacement, following [RFC 6902](https://www.rfc-editor.org/rfc/rfc6902#section-5).
Reuse the existing escaped Deployment path factory and preserve server revisions as strings.

## Testing Plan

Use canonical native preparation/capture fixtures and simulate HTTP transport only.
Pin exact payloads, target correlation, replica bounds and provider preconditions.
Run strict lint, true inference, affected products, package and normal signed gates.
The owner review found that an unexpected current count could exceed the declared delta.
Three regression cases reproduced assertion failures with zero errors before the fix.
The corrected scoped regression passes 16 tests, 146 assertions.
All eight changed namespaces pass true inference with at most three strata.
Seven deliberate request faults cause assertion failures, each with zero errors.

## Deployment Plan

No activation or live effects. Follow all normal review, CI and owner merge gates.

## Related Issues/PRs

N7 completion; #2381, #2392, merged #2407 and preflight #2412.

## Checklist

- [x] Implement request construction and owner adversarial standards pass.
- [ ] Validate real native inputs and packaged behavior.
- [ ] Settle full current-head review, CI and owner merge gates.
