<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Execute bounded native replica provider callbacks

## Overview

Implement the trusted-runtime native Kubernetes replica callback after #2415 and #2416.
This is a provider adapter, not automatic experiment execution or completed N7 runtime composition.
No live experiment, cluster write, new permission or default activation is introduced.

## Motivation

Trial configuration and independent restoration need an actual bounded native provider.
Canonical request data alone cannot execute or substantiate a conditional update.
Reuse the existing HTTP lifetime and dispatch mechanisms instead of copying them.

## Changes in Detail

Share one replica payload factory between the existing planner and the new provider.
Admit only a complete exact staging proposal in a granted, committing deployment transaction.
Require explicit HTTPS settings and a trusted synchronous dispatch callback, with no permissive default.
Bind the context, cluster, namespace, Deployment identity, effect ID, canonical payload and full hash.
Only trial and original-baseline restoration operations are supported by this callback.

Send one conditional JSON Patch through the shared no-redirect HTTP client.
Keep UID and fresh server-revision tests before replacing the replica count.
Use the smaller runtime timeout or declared effect duration.
Reuse owned original futures, cancellation, bounded response reads and strict JSON admission.
Unknown, oversized, malformed or mismatched responses never trigger a retry or success claim.
Return only the exact native resource, server revision and observed desired replica count.

Runtime dispatch remains responsible for current N4/N8/grant rechecks at the mutation boundary.
Record shape is not authority, retained-original-material proof or verified recovery.
API confirmation is not rollout readiness or original-baseline recovery verification.
Load lifecycle, recovery coordination, reconciliation and default-off runner integration remain separate obligations.
N7 remains incomplete.

Protocol follows [Kubernetes conditional updates](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources).

## Testing Plan

Use native capture/preparation/request producers and the real public durable current-commit path.
Simulate only HTTP and explicit trusted-runtime ports; never contact a cluster.
The primary integration invokes the provider inside the real durable commit and reads the final stored record.
Retain shared dispatch fixtures and canonical N10 scope factories instead of duplicating their maps.

Source validation passes:

- Strict entire source and project-test lint: zero warnings or errors.
- All eleven changed namespaces infer to at most three strata, with zero findings.
- Native provider and existing request regressions: 23 tests, 162 assertions.
- Ten deliberate safety faults cause assertion failures, each with zero errors.

Faults bypass proposal binding, repeat admission, response confirmation, cancellation,
response byte limits, UID/revision wire tests or declared-duration enforcement.
Wire-fault setup is independently checked before counting any test result.
An initial lazy-sequence fault-helper error was caught and corrected, not counted as evidence.
The owner also separated ingress admission from dispatch and required a valid native Deployment name.

The fresh full review found missing interruption and deadline checks before HTTP submission.
Three regressions reproduced five assertion failures with zero errors before the fix.
Materialize request options before checking the interrupt flag and remaining deadline.
Refuse before submission with a failed report and zero HTTP calls, preserving interruption.
Only the remaining budget may become the HTTP timeout; preparation time cannot be reused.
The budget regressions use controlled time readings, not sleeps or sub-second success deadlines.

Full validation passes against the actual merged dependencies:

- Workspace composition and phase-opsv tests in all four product contexts.
- All 25 safe OPSV/workflow/N6 JVM integration suites: 128 tests, 1,449 assertions.
- All task entrypoints load, and a fresh CLI artifact builds.
- Isolated Babashka artifact, test paths only: 61 tests, 370 assertions.
- Native packaged compatibility regressions: 308 tests, 1,903 assertions.
- All seven request, seven shared-dispatch and ten native-provider faults are detected with zero errors.

The isolated artifact exercises governance, preflight, request construction, native execution and GitHub compatibility.
No production-source overlay is used. The project test alias explicitly exposes the shared effect fixtures.
Actual main includes merged #2416, and its integration leaves the fully validated tree byte-identical.
The three implementation commits are normally signed at 147, 145 and 135 reportable lines.
The review fix is 74 reportable lines, also under the normal commit limit.
The PR remains within the normal 600-line limit; no size exception or hook bypass is used.
Keep exact-head review, all CI, current-main conflict proof and owner merge requirements unchanged.

## Deployment Plan

No activation or live effects. Runtime composition and separately authorized recovery remain required.

## Related Issues/PRs

N7 completion; #2381, #2392, #2407, #2412, #2415 and #2416.

## Checklist

- [x] Implement the native callback and adversarial source standards pass.
- [x] Prove native input, actual durable-commit collaboration and bounded refusal paths.
- [x] Complete affected products and packaged validation.
- [ ] Settle full current-head review, CI and owner merge gates.
