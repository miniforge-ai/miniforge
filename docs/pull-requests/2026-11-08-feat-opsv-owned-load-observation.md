<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(opsv): observe native load ownership from durable creation and fresh UID

## Layer

Read-only application ownership observation and its native storage/HTTP boundary.

## Depends on

Merged native load creation (#2450) and bounded recovery-plan construction (#2461).

## Overview

Read the plan's actual durable creation transaction, then acquire its exact Pod through bounded native HTTP.
Require the provider-assigned creation UID to match the fresh Pod.
API surface class 1: trusted in-process Clojure/EDN, not an externally authenticated endpoint.

## Motivation

N7 §5.5 requires cleanup to target only proven experiment-owned resources.
A planned name, copied labels, or a generic reconciled verdict cannot prove native creation.
Recovery observation must remain possible after forward grants are revoked.

## Changes in Detail

Require a successful, granted native load-creation record with concrete grant, envelope and provider-receipt identity.
Bind its original, attempt, candidate, pack, service, target, context, effect and payload to the bounded plan.
Reconstruct the sole supported creation request through the existing canonical binding before any provider read.

Read the transaction uncached and use the shared bounded native HTTP reader.
Reject UID/name/namespace substitution, absent identity, malformed input and uncertain creation.
Return the actual creation record and fresh native Pod, not a permission or successful recovery verdict.
Preserve fatal exceptions, interruption and owned-future cleanup.

Changed worker state remains observable for later recovery assessment.
Ownership does not certify current worker configuration, load termination, baseline restoration or deletion permission.
A structurally valid plan is a correlation input, not approval evidence.
The coordinator must separately obtain current retained-plan admission and exact authority before any effect.

No provider mutation, automatic creation retry, gate release, stop, deletion, experiment activation or new
event/artifact type.

## Testing Plan

Use actual native plan construction, root issuance, six-argument pinned commit and durable transaction storage.
Simulate only the HTTP transport.
Cover changed correlation and runtime settings, canonical payload rebinding, copied labels and reused names.
Persist adverse transaction states to prove reads do not use a success cache or promote generic reconciliation.
Exercise actual stored forward-grant revocation without turning read-only observation into recovery permission.
Cover malformed input, missing Pods, timeout cancellation, fatal errors and original interruption identity/flag.

Source and a freshly built isolated package pass 24 tests and 354 assertions.
Both consuming JVM products, Polylith structure and task-classpath checks pass.
Curated integration passes 190 tests and 2,362 assertions; native compatibility passes 308 tests and 1,903 assertions.
The isolated package uses only the current JAR and test directories, without production-source overlays.
Strict lint is zero-warning; all seven genuinely inferred namespaces remain within three strata.
All six injected faults cause assertion failures with zero errors.
They bypass plan binding, creation schema, canonical wire binding, live identity, future cancellation and fatal propagation.
The JVM pass caught a Java class-name collision that Babashka allowed; renaming the schema fixed it without an import override.

## Standards Gap Analysis

Pinned catalog: `72f451d3d7e0ad82079610bec61be0399a2b7117`.
The owner reviewed the actual final source, tests, dependency traces and applicable pinned rules after formatting.
Reuse the existing transaction store, canonical load reconstruction, shared schemas and bounded HTTP reader.
Keep identity correlation in a pure model, acquisition in the boundary and vocabulary in schemas.
Use localized diagnostics and no copied effect-authority construction.
No additional standards gap was found in the scoped final pass.

## Deployment Plan

No default activation or live cluster operations.
Retained-plan approval, lifecycle commands, current grants and verified recovery remain separate integration obligations.

## Related Issues/PRs

N7.EX.6; N7.VF.4; N10 §1.5; #2440; #2450; #2461; #2467.

## Checklist

- [x] Actual-diff owner adversarial and applicable pinned-rule review
- [x] Affected-product, integration, isolated-package and deliberate-fault validation
- [x] Normal signed commits and hooks
- [ ] Current-head full Copilot review, resolved comments, CI and clean merge
