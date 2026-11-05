<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Acquire declared Prometheus criterion observations

## Overview

Compose bounded Prometheus windows into explicitly declared numeric criterion
observations for the native OPSV backend, preserving original measurements.
API surface class 1: internal EDN and trusted-process composition, not an external wire API.

## Motivation

Native verification needs real metric observations without guessing aggregates,
rewriting criteria after measurement or fabricating success/confidence/evidence.

## Changes in Detail

Expose `observe-prometheus-criteria!` with complete target/service/label
declarations and unique criterion IDs. Admit finite, metadata-free configuration,
closed schemas and every declared sample grid before querying. Reuse the existing
Prometheus client and shared discovery label correlation. Resolve effective
endpoint settings at batch admission and check every request budget against the
endpoint cap before collection. Carry the resolved settings into normal client
calls; preserve original requests without clamping their declared budgets.

Require explicit maximum, minimum or last reductions; never guess an aggregate.
Preserve each original decoded query/window/labels/sample vector and exact
declared request/budget and reduction. Return numeric observations by criterion
ID, original windows and the declared target/service/label names. Endpoint
credentials do not enter that declaration. Acquisition stops on the first
failure, with no partial observation result.

These are sensitive observations, not native UID attestation, confidence,
artifact IDs, retained evidence, experiments or verification success. Reads are
bounded individually, not an atomic batch. Runtime composition must independently
bind plans to the retained attempt/candidate/pack and enforce N6 and execution
authority. This API does not establish those conditions or activate a backend.

## Testing Plan

Simulated transport only. Test declared reductions, label binding, no partial
results, bounded admission, invalid grids, interruption and provenance. Serialize
all local JVM/build/hook runs; preserve every review and merge gate.

Verified at `29587c3f4e1300c01cd67adb2a6ac9765312efeb`:

- Strict kondo: zero errors/warnings; all 12 changed Clojure namespaces pass
  inferred strata and poly check; both phase-OPSV product contexts pass.
- All 20 scoped OPSV/workflow/N6 integration namespaces: 88 tests, 1,142 assertions.
- Fresh CLI, isolated native regression package: 267 tests, 1,582 assertions;
  isolated criterion suites: 12 tests, 111 assertions; existing client: 15 tests,
  49 assertions.
- Source suites including existing discovery/client regressions: 34 tests, 205 assertions.
- Eleven new fault injections detected, zero test errors, plus all 11 preparation,
  14 producer-privacy and 19 native-reporting fault injections detected.
- Five unit cases/32 assertions all below 100 ms. Committed scans: zero
  violations/312 component files and zero/18 project OPSV files.
- PR: 344 reportable lines; signed implementation/test/fix commits of 168, 141
  and 41; no size exceptions.

The effective-cap regression failed against the published implementation: 12
tests, 111 assertions, one failure and zero errors (an earlier HTTP read occurred).
The fix rejects a later over-cap request before any read; the positive endpoint
cap case preserves the distinct declared budgets. The new preflight-bypass fault
is detected independently. One shared cap predicate serves batch and existing
single-window admission without changing the latter's behavior.

Factories use actual decoded ranges and new HTTP bodies for every acquisition.
All HTTP is simulated. Existing discovery regressions remain unchanged. No tests
or assertions disabled; unrelated local executor lifecycle suites can mutate
configured backends and were not invoked. Complete isolated CI remains required.

## Deployment Plan

Read-only measurement only. No load, experiment, mutation, authority, evidence
publication or automatic backend activation.

## Related Issues/PRs

N7 verification criteria; bounded Prometheus client and native discovery #2374.

## Checklist

- [x] Implement and adversarially review the measurement composition.
- [x] Complete local source, project, package, fault and standards gates.
- [ ] Settle review, require fresh full review/CI, and owner-merge.
