<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Submit governed native Kubernetes load creation

## Overview

Provide the trusted-runtime callback for creating one canonical, scheduling-gated load Pod.
Base: main, after merged #2443. Layer: native provider execution.
API surface class 1: trusted internal Clojure/EDN, not a public HTTP endpoint.
No workflow activation or live experiment is introduced.

## Motivation

Native load proposals need an execution boundary, not a second request generator.
Creation must preserve current authority checks, exact payload binding, bounded
transport, and honest uncertainty before later gate release and recovery integration.

## Changes in Detail

Reconstruct the sole supported Pod request through the existing canonical factory.
Validate explicit runtime settings and worker bounds at the external boundary.
Require the exact committing load-test record and trusted current N4/N8/grant dispatch.
Reject rehashed arbitrary commands, paths, resources, or Pod configuration.

Share HTTP lifetime ownership, cancellation, byte limits, strict JSON and no-retry
behavior with the replica provider. Preserve HTTP OK for replica updates and
require HTTP Created for new load resources. Do not discover credentials or clusters.

Accept a creation receipt only with a provider-assigned UID and revision,
requested fields, exact vector lengths/order, and the unchanged scheduling gate.
Refuse owning controllers, bound nodes and added init or ephemeral workers.
API default map fields are allowed; this receipt is not a full execution attestation.
The approved recovery plan, fresh namespace/context checks, separately authorized
gate release and owned-resource cleanup remain runtime integration obligations.
No resource is considered owned merely because its name matches.

## Testing Plan

Use actual native acquisition, protected original retention, root grant issuance,
and six-argument current durable commit. Simulate only HTTP transport and the
trusted dispatcher in offline provider tests. Exercise scope/runtime refusals,
rehashing, late or duplicate dispatch, deadlines, strict responses and changed workers.

The initial focused run passed 44 tests / 494 assertions after correcting the
test producer to retain the complete canonical proposal.
Adversarial extra-argument and extra-worker regressions failed before tightening
response matching, with two assertion failures and zero errors.
Run strict lint, actual inferred strata, both consuming JVM products, curated
integration, fresh isolated-package suites and focused fault checks.

Passed strict lint with zero warnings/errors and all 20 changed namespaces at
three or fewer genuinely inferred strata. Both consuming JVM products and
Polylith check passed. The curated integration suite passed 160 tests / 1,924 assertions.
The fresh isolated CLI package passed 44 tests / 496 assertions, and native
compatibility passed 308 tests / 1,903 assertions, with no production source overlay.
All ten injected faults produced assertion failures and zero test errors.

## Deployment Plan

No default activation, cluster writes, image pulls, load traffic or credential changes.
The callback is available only to explicitly composed trusted runtime code.
Unknown creation outcomes require reconciliation; they never trigger another POST.
Creation does not release the scheduling gate or prove load termination or recovery.

## Related Issues/PRs

N7 §5.5 and §6; N10 §1.5; #2443 canonical gated load proposals; #2441 recovery issuance.

## Checklist

- [x] Complete whole-diff adversarial and applicable standards review.
- [x] Pass offline source/JVM/package/fault validation.
- [ ] Pass normal signing and hooks.
- [ ] Settle current-head review, fresh full review and CI; owner-merge.
