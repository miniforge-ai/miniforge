<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): prepare exact owned-load stop requests

## Overview

Prepare canonical stop-load proposals from a freshly admitted retained recovery
plan and fresh durable/provider ownership. Construction is read-only and does not
issue authority, change Kubernetes state, or prove the worker terminated.

## Motivation

N7 compensation needs separately authorized exact requests, even after forward
execution stops. Bind the concrete Pod UID and current resource version immediately
before separately obtaining current effect admission and authority.

## Changes in Detail

Read retained intent uncached and require fresh independent N4 plan admission
before acquiring the actual durable creation receipt and matching live Pod UID.
Require a separate stop effect identity and a positive current Pod deadline.
Build the sole supported JSON patch: test the exact UID and current resource
version, then reduce the deadline to one positive second. Hash the exact request
and reuse the shared canonical proposal factory with the admitted recovery bound.

Reject changed creation payload, original, service or target, caller approval
flags, uncertain creation, foreign UID and revoked retained-plan approval.
Reconstruct each request from current state; do not cache approval or revision.
Read-only recovery preparation remains available after actual forward-grant
revocation. An elapsed deadline or successful patch is not termination proof.
Native execution, current effect admission, separate recovery grant issuance,
pinned commit and successful recovery remain separate obligations.

## Testing Plan

Use actual retained admission, root issuance, durable creation and fresh ownership.
Simulate HTTP only. Verify exact request bytes, revocation, substitutions and
uncertain receipt handling. Run source, JVM and isolated-package checks.

Both consuming JVM products, Polylith and task-classpath checks pass. Source and
the fresh isolated package pass 44 tests and 704 assertions. Broader integration
passes 210 tests and 2,712 assertions; native compatibility passes 308 tests and
1,903 assertions. Packaging uses only the new JAR and test directories, without
production-source overlays. Strict lint is zero-warning; all six actual call
graphs fit within three strata. All four injected faults cause assertion failures
with zero errors: bypassed closed input, distinct identity/deadline admission,
and each exact UID and resource-version precondition.

## Standards Gap Analysis

Pinned catalog: `72f451d3d7e0ad82079610bec61be0399a2b7117`.
Keep structural vocabulary, pure canonical request construction and bounded
acquisition separate. Reuse real retained admission and ownership, the canonical
proposal factory, localized diagnostics and existing root/transaction fixtures.
Do not manufacture effect authority or copy authoritative approval maps.
The owner traced the final actual source, tests, retained admission and native
ownership producers against all applicable pinned rules after formatting. No
additional standards gap was found in the scoped pass.

## Deployment Plan

No activation, live experiments, traffic, cluster mutations or credential changes.

## Related Issues/PRs

N7 §5.5; N10 §1.5; #2475; #2478.

## Checklist

- [x] Implement and validate the scoped request constructor.
- [x] Owner adversarial and applicable pinned standards review.
- [ ] Normal signed commits, budgets, full review and all CI gates before merge.
