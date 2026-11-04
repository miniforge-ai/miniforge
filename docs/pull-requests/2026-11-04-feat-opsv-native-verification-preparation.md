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

Implementation in progress on main including merged producer privacy #2371.
Reuse existing public contracts and native capture/projection APIs. Retain all
integration, review and merge gates.

## Testing Plan

Fail closed before capture for malformed, mismatched or tampered requests.
Use injected read transport only. Prove original capture and semantic views,
correlation, recovery-bound selection and protection at N6 publication.
Serialize all local JVM/test/build/hook runs through the shared slot.

## Deployment Plan

Read-only preparation only. No live experiment, configured-cluster operation,
runner actuation, CLI execution authority or automatic backend activation.

## Related Issues/PRs

N7 section 6.3; native capture #2366, fingerprint #2370, producer privacy #2371.

## Checklist

- [ ] Implement and adversarially review the bounded callback.
- [ ] Complete local product, package, fault and scan checks.
- [ ] Require current-head CI and settled fresh full review before owner merge.
