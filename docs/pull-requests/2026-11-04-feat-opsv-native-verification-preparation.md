<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Prepare native verification from a bound comparison contract

## Overview

Connect bounded read-only native capture and canonical projection to the existing
verification-preparation callback. No experiment, load or cluster mutation.

## Motivation

Native observations must match the independent workflow, attempt, candidate,
Experiment Pack and target before acquisition. Recovery bounds come from the
matching predeclared pin, never from observations adopted as expectations.

## Changes in Detail

Expose `prepare-kubernetes-verification!` and its independent workflow/Deployment
declaration. Admit bounded, metadata-free originals before validation or capture.
Validate the host's seven-field request, independently bind workflow and target,
rehash policy/pack payloads and verify pin self-consistency. Require complete
candidate/recovery membership; select recovery bounds for native projection.

Reuse bounded native capture and canonical projection. Return the correlated
receipt, original semantic observations and explicit native reporting frame.
Repeat calls reacquire configuration; missing runtime cannot borrow expectations.
No duplicate native policy, scanner or persistence implementation.

The host creates the pin before this read-only callback and retains its N6
contract/receipt afterward, before candidate effects. This callback establishes
neither durability nor an environment-match, health or authorization verdict.
Original values reach host comparison and shared PII scanning; only protected
copies are published. New functions compose downward in inferred strata.

## Testing Plan

Fail closed before capture for malformed, mismatched or tampered requests.
Use injected read transport only. Prove original capture and semantic views,
correlation, recovery-bound selection and protection at N6 publication.
All local JVM/test/build/hook runs were serialized through the shared slot.
Verified at `dbbc23da3dcf3739e7b97ec9963ced51f32bf6ae`:

- Strict clj-kondo: zero errors/warnings; changed inferred strata and poly check pass.
- Phase-OPSV product contexts pass; all 19 scoped OPSV/workflow/N6 integration
  namespaces pass: 81 tests, 1,063 assertions.
- Fresh CLI build and isolated packaged tests: 267 tests, 1,582 assertions.
- New source suites: eight tests, 93 assertions; unit guard: 1.62 ms, eight assertions.
- Eleven new fault injections detected, with zero test errors; all 14 prior
  producer-privacy and 19 native-reporting fault injections also detected.
- Committed component scan: zero violations/306 files; project OPSV integration
  scan: zero violations/16 files. PR: 384 reportable lines; no size exception.

Unrelated local executor lifecycle suites can mutate configured backends and
were not invoked. No tests/assertions disabled; repository-wide isolated CI
remains required unchanged. Actual capture uses simulated transport; host tests
use owned temporary N6 storage and prove finalization preserves original PII
findings without publishing literal confidential values.

## Deployment Plan

Read-only preparation only. No live experiment, configured-cluster operation,
runner actuation, CLI execution authority or automatic backend activation.

## Related Issues/PRs

N7 section 6.3; native capture #2366, fingerprint #2370, producer privacy #2371.

## Checklist

- [x] Implement and adversarially review the bounded callback.
- [x] Complete local product, package, fault and scan checks.
- [ ] Require current-head CI and settled fresh full review before owner merge.
