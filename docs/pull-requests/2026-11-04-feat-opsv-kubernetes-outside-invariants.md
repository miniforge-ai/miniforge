<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Preserve independent outside-workload drift invariants

## Overview

Derive a fixed, versioned projection of captured resources outside a natively
proven Deployment, its ReplicaSets and its Pods. Retain raw input separately.

## Motivation

Permitted replica replacement must not hide unrelated changes. Labels cannot
establish ownership. Node names cannot establish physical Node identity, and
desired configuration alone omits native controller links and deletion state.

## Changes in Detail

Base branch: main, including merged verification capture #2366. Private history
was reconciled with whole-prerequisite tree equality and feature-tree preservation.

Reuse native inventory partition and complete capture admission. Normalize
omitted list-item TypeMeta only after schema admission. Refuse incomplete Node
inventories and global duplicate physical or logical identities.
Only an identical Namespace serving both cluster identity and target is coalesced;
duplicate collection entries remain invalid.

Admit present outside-Pod runtime records before projection. Status collections
and entries must have valid shapes, unique names and declared-container linkage.
Absent, empty and partial reports remain explicit for Pending Pods; they do not
become complete runtime-image proofs. Selected replica admission stays strict.

Exclude exactly the proven Deployment, ReplicaSets and Pods by UID. Keep cluster
and namespace identities, captured Nodes, configuration and unrelated workloads.
The projection retains desired configuration, native owner references, finalizers,
deletion metadata and observed runtime images or Node runtime information.
Bind cluster interpretation and the configured pool-label key into its hash.

Reuse the existing desired resource view, which excludes status and bookkeeping
metadata except the explicitly retained lifecycle and runtime fields. Secret
configuration uses its server resource version, never Secret data or annotations.
Order resources canonically; retain the versioned semantic view beside its hash.

This is an outside-only comparison value, not a complete OPSV fingerprint.
Owned controller desired configuration and counts, replica invariants, freshness,
scaling stability and SLO health need independent admission before success.
Protect reporting copies after hashing original values. No storage, I/O,
experiments or execution authority is introduced.

## Testing Plan

Prove owned replica replacement and explicit zero populations preserve outside
invariants. Prove unrelated ownership, lifecycle, image, configuration and Node
identity changes remain visible. Cover ordering, omitted TypeMeta, duplicate and
incomplete inputs, Secret treatment and unsupported content.
Run product, isolated package, mutation and complete PR gates.

Both product contexts and poly check pass. Fresh isolated native packaging passes
146 tests and 855 assertions. Deliberate bypasses are detected without test errors.
The 19 new cases contain 83 assertions and measure below 100 ms each.
The committed component scan reports zero findings.

## Deployment Plan

Explicit pure API. Native preparation and runner composition remain separate.

## Related Issues/PRs

N7 section 6.3; replica observations #2365 and verification capture #2366.

## Checklist

- [x] Preserve independent outside-workload drift evidence and raw input.
- [ ] Complete owner, product, package, CI and full-review gates.
