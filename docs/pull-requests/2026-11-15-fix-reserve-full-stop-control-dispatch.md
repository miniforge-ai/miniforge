<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: reserve full stop for dedicated containment authorization

## Overview

Reject full stop at the ordinary control authorization boundary. A
caller-provided role map cannot authorize execution through that route.

## Motivation

N8 §3.5 requires N4 run-scoped authorization and applicable Critical
approval before containment. The ordinary executor has no corresponding
run binding or approval proof. It must not become a second route.

## Changes in detail

Domain layer. Reserve `:full-stop` in the shared ordinary authorization
decision, independent of the caller's allowlist. Preserve existing role,
target, and denial handling for other actions.

## Testing plan

Pass an explicit full-stop allowlist through the public authorization
and execution APIs. Assert denial and verify the executor is not called.
Run adjacent authorization, execution, and audit regressions.

Local JVM and Babashka runs each passed 23 tests and 143 assertions.
The new regression failed all three assertions on the old implementation,
with no runtime error. Strict lint and inferred namespace depths passed.

## Deployment plan

No provider commands or infrastructure changes. This guard does not
implement full stop or grant containment authority.

## Related issues/PRs

PR #2528 defines the approved containment contract.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).
