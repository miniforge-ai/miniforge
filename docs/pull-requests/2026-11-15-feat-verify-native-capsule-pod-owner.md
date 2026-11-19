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

## Testing plan

Mock all provider responses. Reject a reused Job name with a different UID,
an invalid controller, a different scope, a terminating Pod, and an
unconfirmed identity. Verify that acquisition errors do not delete any
resource by name.

Both JVM products, Babashka source, and the fresh package passed 23 tests
and 135 assertions each. Omitting controller validation failed three targeted
assertions, and ignoring the read timeout failed one. Neither caused
runtime errors. Strict lint, namespace depths, Polylith, and Babashka
classpaths passed.

## Deployment plan

No live provider actions. This verifies an admission-time identity snapshot.
It does not authorize containment, pin cluster identity, or change ordinary
release and execution after admission.

## Related issues/PRs

PR #2534 provides controller identity matching. PR #2536 retains the
Job creation receipt.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
