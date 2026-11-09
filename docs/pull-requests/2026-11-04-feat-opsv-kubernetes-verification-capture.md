<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Capture a bounded native verification environment

## Overview

Capture complete namespace workload and cluster Node inventories, cluster identity
and configuration dependencies under one deadline and object budget.

## Motivation

Native preparation needs fresh observations before candidate effects. Running
Pods alone omit desired dependencies at zero replicas and older controller
templates. Separate acquisition calls also lose the shared budget and deadline.

## Changes in Detail

Base branch: main, including merged replica observations #2365. Private history
was reconciled with whole-prerequisite tree equality and feature-tree preservation.

Reuse namespace inventory acquisition, typed resource reads, dependency admission
and request construction. Read dependencies from every captured Pod, Deployment
template and ReplicaSet template, deduplicating references and preserving required
versus optional semantics. Pending Pods need no runtime status for this capture.

Read one complete unselected NodeList, not a synthetic list of individual GETs.
Reserve the cluster identity, namespace resources, Nodes and dependency reads
against one object budget. Every request shares one deadline and response bound.
Never send a zero list limit, which Kubernetes interprets as unbounded.

Recheck namespace, Deployment and cluster identity after dependencies are read.
Conservatively refuse any root-record change, including resource-version changes.
This bounded multi-read is not an atomic cluster snapshot.

Keep initial and confirmed inventory roots separate internally. Verification
requires unchanged roots and preserves the initial records. Legacy namespace
inventory still reports its confirmed latest roots under the existing UID checks.

Return raw observations with an explicit schema. Raw values can contain secrets;
consumers must compute original comparison hashes before protecting reporting
copies for N6 storage. This API does not publish or retain evidence.

No live calls occur during implementation or tests. No membership verdict,
verification result, permission, experiment or mutation authority is established.
Semantic environment projection and runner wiring remain separate.

## Testing Plan

Use injected native-shaped HTTP responses only. Cover shared bounds, zero replicas,
desired and old-controller dependencies, Pending and unrelated Pods, incomplete
Nodes, malformed references, missing configuration and late root changes.
Run product tests, isolated packaging, mutation checks and all PR gates.

Both affected product contexts and poly check pass. Fresh native packaging passes
127 tests and 772 assertions; the 14 new cases contain 71 assertions. Eight
deliberate bypasses are detected without test errors. The committed source scan
reports zero findings across the committed component. New cases measure below 100 ms.

## Deployment Plan

Explicit read-only API. No endpoint or credentials are discovered automatically.
Existing fingerprint and namespace-inventory acquisition retain their contracts.

## Related Issues/PRs

N7 section 6.3; inventory acquisition #2361 and replica observations #2365.

## Checklist

- [x] Cover native capture, dependency scope and late-root admission.
- [ ] Complete product, package, owner, CI and full-review gates.
