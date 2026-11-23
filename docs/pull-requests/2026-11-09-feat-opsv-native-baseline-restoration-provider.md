<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): execute exact original-baseline replica restoration

## Overview

Submit an exact conditional restoration of the original Deployment replica
count after fresh retained recovery admission and native capture.
Acknowledgement is not proof of restored runtime invariants.

## Motivation

N7 §5.5 and §6.3 require independently authorized original-baseline recovery.
Read-only request preparation cannot execute recovery or establish its success.

## Changes in Detail

Reuse the original-baseline request constructor and existing bounded conditional
replica HTTP provider. Reconstruct current intent before dispatch and preserve
exact UID/revision fencing and recovery authority. Hydrate the existing validated
native settings before delegation; partial capture overrides are not complete
effect settings. This mismatch was exposed and corrected by the first integration
run, before any patch was sent.

Share actual durable proposal and rebind fixture factories with load recovery.
Preserve their independent grant stores, issued pins and record semantics.

## Testing Plan

Use actual retained plan approval, fresh native capture, independent root
recovery issuance and pinned durable transactions; simulate HTTP only.
Cover changed target, original-count substitution, revoked current authority,
independent grant pins, uncertain receipts and synchronous provider ownership.

- Strict lint: zero warnings/errors; every checked namespace has at most three inferred strata.
- Polylith structure, both consuming JVM products and task classpaths pass.
- Source and isolated fresh package: 139 tests / 4,394 assertions each.
- Curated OPSV/workflow/artifact integration: 302 tests / 6,393 assertions.
- Native compatibility: 308 tests / 1,903 assertions across 42 suites.
- Thirty-eight faults each trigger assertion failures with zero errors.
  These include six restoration, six native-start, ten start-constructor,
  seven cleanup and nine native-cleanup faults. They cover canonical requests,
  exact scope, runtime ingress, receipts, status policies and synchronous ownership.

## Deployment Plan

No live restoration or experiment is authorized. Current N4/N8 recovery admission,
exact independent recovery authority and a pinned commit remain required.
Separate runtime verification must prove original-baseline invariants afterward.

## Related Issues/PRs

Base: main. Depends on the original-baseline restoration constructor.
Publish only after that prerequisite is merged and combined validation passes.

## Checklist

- [x] Prerequisite merged and exact native provider implemented.
- [x] Source, JVM, isolated package and fault-injection validation.
- [x] Owner adversarial and full applicable standards review.
- [ ] Normal signed commits, full current-head review, CI and owner merge.
