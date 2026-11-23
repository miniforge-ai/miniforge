<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Extract the workflow runner vocabulary

## Overview

Prepare the runner for fail-closed lifecycle publication without adding more
responsibilities to its cyclic, deeply layered namespace. This prerequisite
extracts context preparation, outcomes, one-step execution, the bounded loop, and
callback ownership into distinct namespaces. It does not switch the runner yet.

## Layer and dependencies

Application-layer decomposition, based on main. No open PR dependencies.
The follow-up integration removes the old implementations and preserves the public
runner API. Temporary duplication exists only until that atomic integration.

Preparation accepts the nested runner as an explicit port. Outcomes do not depend
on execution. Iteration uses outcomes; the bounded loop uses iteration and outcomes.
Callbacks own heartbeat lifetime and preserve caller arguments and ordering.

## Changes and compatibility

- Preserve governed capsule refusal, resume assembly, and environment selection.
- Preserve retry caps, supervision, backoff, checkpointing, and terminal projection.
- Replace nested callback closures with named operations and partial application.
- Separate supervision from phase execution and consolidate pipeline-failure updates.
- Check the executor protocol before runtime-class projection instead of catching
  exceptions for unsupported values. Errors from valid executors are not hidden.
- Keep callback publication behavior unchanged; durable receipt handling follows.

## Verification

Direct acceptance checks cover the explicit execution port, governed refusal,
callback order and cleanup, and the consecutive-retry safeguard. Verify lint,
inferred strata, composition, serial consumer tests, and the packaged CLI before
opening the PR. Run the standards audit on the committed diff, not the worktree.

All three workflow consumer suites passed in a serial run (3m01s). The rebuilt
CLI passed the isolated helper acceptance suite: 5 tests, 19 assertions. Lint and
inferred strata have no findings. The final committed-diff scan follows commits.

## Deployment

No default behavior changes in this prerequisite PR. Integrate the vocabulary in a
separate bounded PR before adding fail-closed lifecycle callbacks.
