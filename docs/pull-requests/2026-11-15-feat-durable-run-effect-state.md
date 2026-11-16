<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: persist monotonic run effect authority state

## Overview

Add the storage kernel for the operator-approved full-stop boundary in #2528.
It does not implement full stop, authorize an intervention, or fence dispatchers.

## Motivation

Forward cancellation cannot stand in for an irreversible all-workload stop.
Run authority needs a durable state that missing files cannot silently reactivate.

## Changes in detail

Foundation layer, dependent on the protected-directory extraction in #2529.
Existing execution-grant owns the state; no second capability broker is added.
API surface class 1: trusted host inputs are validated at the component interface.

Run registration reserves an identity directory before create-only publication.
Existing active records can be confirmed; missing reserved state cannot be
registered again. Full stop atomically replaces state and can precede registration.
There is no operation to reactivate stopped state. Reads deny missing, malformed,
linked, mismatched, or unprotected state rather than interpreting absence as permission.
Only qualified POSIX directories are supported; native defaults are refused.

The grant reader accepts an explicit identity key, preventing confused identities.
Run and grant records share strict UTF-8, EDN, schema, and link checks.

## Testing plan

Run both JVM products' complete execution-grant suites. Run the new run-state
suite and explicit old-grant lookup regression against source and a fresh package.
Legacy JVM fixtures use classes unavailable in Babashka, so they remain JVM tests.
Inject faults in stopped registration, reservation retention, record identity,
and actual publication; require assertion failures without test errors.
Run strict lint, inferred namespace-depth checks, workspace and classpath gates.

Review fixes add cross-process transition serialization, protected file contents,
reservation-before-publication forcing, and lossless printer-independent UTF-8.
Busy transitions return conflict; retry confirms the first acknowledged stop.
Tests cover concurrent distinct requests, another process holding the OS lock,
writable records, failed reservation forcing, malformed Unicode, and printer bindings.

Validation: both JVM products passed 102 tests and 769 assertions.
The real-process integration test passed four assertions. Babashka source and
isolated fresh package each passed 16 tests and 62 assertions, including old
grant lookup. Eight safety faults produced assertion-only failures in every
runtime. Strict lint, inferred namespace-depth, workspace and task-classpath
gates passed, including the final codec and transition-lock decomposition.

## Deployment plan

No caller is wired to these storage APIs in this increment. The host admission,
issuance, and dispatch integration follows separately, with N4 authorization,
N3/N6 audit, and N11 owned-runtime containment. Storage success alone cannot mean
the runtime or an already accepted external request was stopped.
Registration is for new-run admission, not reconstruction from missing storage.
Durable workflow identity, authority-root binding, and terminality remain the
host's responsibility.

## Related issues/PRs

PR #2528 defines the approved full-stop contract, including containment-only
audit-outage handling. Merged PR #2529 supplies the protected-directory policy.

## Checklist

- [x] Adversarial standards pass complete.
- [x] JVM and fresh-package proofs complete.
- [ ] Current-head CI and Copilot review settled.
