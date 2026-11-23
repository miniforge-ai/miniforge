<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: verify exact Pod ownership before admitting a capsule

## Overview

Treat legacy readiness as a candidate observation. Re-read that Pod and
confirm its controller against the Job creation receipt before admission.

## Motivation

A Job name and a Running status do not prove that the Pod belongs to the
Job created for this capsule. Do not adopt or delete by name on a failed
acquisition.

## Changes in detail

Runtime boundary. Read Pod JSON by explicit name. Verify its namespace
and its controller's complete identity, and reject terminating or
non-Running observations. Keep the observed Pod UID and version in
the environment record. An acquisition with missing evidence does not
issue a name-only delete; the Job's admitted deadline remains in force.
The Pod identity read has its own validated timeout, independent of the
Job creation request and the ongoing readiness polling.
The default readback budget is five seconds; invalid overrides disable the executor
before any provider call. Acquisition uses the merged cancellation-fenced handoff:
an interrupt before, during or after identity validation cannot cache a candidate.
The same bound stops an uncooperative local readback process, not just
the provider request. An interrupted readback remains a refusal and
preserves cancellation.

## Testing plan

Substitute only native shell/process I/O in the composed admission cases, retaining
the real creation, readiness and ownership rules. Reject a reused Job name with a different UID,
an invalid controller, a different scope, a terminating Pod, and an
unconfirmed identity. Verify that acquisition errors do not delete any
resource by name.

Both JVM products, Babashka source, and the fresh deployment package pass 36 tests
and 206 assertions each. The native script derives Pod/controller facts from the
actual fake Job response; its three read boundaries are all intercepted.
Omitting controller validation fails three targeted assertions. Ignoring the final
deadline, restoring name-only failure cleanup, or dropping typed cancellation each
fails one. Every targeted control has zero runtime errors on JVM and Babashka.
The cleanup control refuses to run unless the native shell fixture is installed.
Four safe, exact namespace examples pass on both runtimes; legacy examples that
contact providers are not evaluated. Strict lint, genuine namespace depths of at
most three strata, Polylith and normal commit gates pass.

## Deployment plan

No live provider actions. This verifies an admission-time identity snapshot.
It does not authorize containment, pin cluster identity, or change ordinary
release and execution after admission.

## Related issues/PRs

Merged PR #2534 provides controller identity matching. Merged PR #2536 retains the
Job creation receipt and validated executor timing settings.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
