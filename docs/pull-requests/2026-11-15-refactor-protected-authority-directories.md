<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: share protected storage directory policy

## Overview

Extract the checkpoint directory protections into the filesystem foundation.
Checkpoints continue using the same ownership, permissions, and ancestry rules.
Durable run authority can then reuse them without a second security policy.

## Motivation

Full-stop fencing needs storage that another account cannot replace. Reusing
the existing protection avoids divergent checkpoint and authority implementations.

## Changes in detail

Foundation layer; base `main`. No implementation prerequisite.
API surface class 1: trusted-process Files in, resolved Paths/profiles or
anomalies out. Native filesystem defaults remain explicitly unqualified.
The intended consumer is durable run authority under the full-stop contract
in #2528. This PR does not implement full stop or authorize any live operation.

## Testing plan

- Preserve checkpoint directory, permission, and locking regressions.
- Add filesystem-boundary coverage for protected creation and unsafe ancestry.
- Run strict lint, actual stratum checks, workspace checks, and relevant product
  tests; run normal signing/hooks and current-head CI/review gates.

Validation completed:

- Both JVM products: 83 tests, 291 assertions each before the probe hint;
  targeted owner-lock delta afterward: 11 tests, 46 assertions each.
- Fresh CLI, source overlays, and isolated package: 83 tests, 292 assertions
  per Babashka run, plus four assertion-only safety faults in each run.
- Strict lint: zero warnings/errors. Actual strata: 17 namespaces, at most
  three. Workspace and task-classpath checks passed.

## Deployment plan

No new runtime configuration or live effects. Checkpoint behavior remains
unchanged; failures retain the checkpoint I/O exception boundary and localized
checkpoint diagnostics. A native-provider regular-file resolution hole found
during extraction is closed and has a regression test.
Packaged regressions also exposed an untyped owner-lock probe release that
Babashka rejects. It now uses the same AutoCloseable hint as the existing
release path; repeated probes verify that the temporary lock is released.

## Related issues/PRs

PR #2528 defines the approved full-stop contract; implementation verification remains pending.

## Checklist

- [x] Adversarial standards pass complete.
- [x] Source and packaged regressions complete.
- [ ] Current-head CI and Copilot review settled.
