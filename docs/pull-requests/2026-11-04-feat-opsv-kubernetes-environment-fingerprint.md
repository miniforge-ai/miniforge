<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Compose native Kubernetes environment fingerprints

## Overview

Compose existing native observations into the canonical OPSV environment
fingerprint. Preserve original capture, semantic projections and instance-level
observations separately. This is an observation primitive, not verification,
execution authority or the native runner.

## Motivation

The captured resource profile is not the canonical four-field environment
fingerprint. Replica replacement requires stable configuration comparisons while
retaining physical identities and count evidence for independent admission.

## Changes in Detail

Base branch: main, including verification capture #2366, outside invariants #2367
and bounded controller configuration #2369. No unmerged prerequisite is included.

Add `project-kubernetes-environment-fingerprint`, taking a complete capture and
independently predeclared count bounds. Reuse outside, controller and strict
replica projections; validate before composing their original-content hashes.
The caller must bind bounds to the retained target and attempt before effects,
then compare observations against that independent contract.

The canonical value contains cluster alias, sorted observed Node pool inventory,
actual runtime image digests and a versioned configuration hash. Reject absent or
heterogeneous runtime evidence; never substitute expected images as observations.
Empty populations cannot establish this primitive's nonempty runtime fingerprint.

Configuration binds outside resources, bounded owned desired configuration,
replica template and selected-Pod lifecycle. Preserve finalizers, deletion fields,
noncontroller owner references and controller-role flags. Only the already-proven
controlling parent name/UID is omitted from the replacement-stable lifecycle view;
actual controller identities remain in the raw capture and controller projection.
Validate selected metadata before canonicalizing unordered native fields.

Keep original per-Pod lifecycle views by UID alongside sorted distinct lifecycle
hashes. This normalization does not establish population bounds, scaling stability
or SLO success. Preserve controller counts, replica observations and all underlying
semantic projections for the later contract comparison and evidence lineage.

Pure functions only: no cluster calls, effects, storage or authority. Fresh capture,
preparation callback, protected N6 retention and runner wiring remain separate.
Protect sensitive copies before reporting or storage, after original comparisons.

## Testing Plan

Cover canonical shape, original source/target, replacement churn, count
normalization, outside drift, desired configuration and runtime differences.
Reject missing/malformed capture, invalid bounds and heterogeneous runtime.
Cover selected-Pod lifecycle, noncontroller ownership and native metadata ordering.

Both product contexts, poly check and a fresh CLI build pass. Isolated packaging
passes 191 tests and 1,068 assertions without a production-source overlay.
The two component cases contain 17 assertions and measure below 60 milliseconds.
Ten composed scenarios contain 39 assertions in the project integration suite.
All twelve cases pass, using shared native-shape fixture factories.

Eleven deliberate faults break assertions with zero test errors.
They cover outside, controller, template and lifecycle hashes; homogeneity;
runtime images; Node pools; source and replica observations; configurations; and count bounds.
The committed native scan reports zero findings across
289 component files. Strict full-source/new-test lint and explicit strata pass.
Owner review covers every changed file and all applicable standards. No signing,
size, hook, review or CI gate is waived. Final evidence changes documentation only
against tested source c0ae008c1ee6957c9991cbd1a0c451640927480a.

## Deployment Plan

Explicit observation API only. No live experiments or cluster changes.

## Related Issues/PRs

N7 section 6.3; native verification capture #2366 and controller configuration #2369.

## Checklist

- [x] Preserve original observations separately from semantic comparisons.
- [x] Main-agent adversarial review caught and fixed selected-Pod lifecycle loss.
- [x] Place tests by measured runtime, without changing their assertions.
- [ ] Complete product, package, CI and exact-head full-review gates.
