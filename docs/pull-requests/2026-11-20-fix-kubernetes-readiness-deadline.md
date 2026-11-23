<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: bound native capsule readiness polling

## Overview

Share one monotonic deadline across Pod listing, status reads, output capture
and polling waits. Refuse late Running replies and preserve cancellation.

## Motivation

The old readiness loop checks time only between unbounded CLI requests.
Either read can hang, and an oversized polling interval can exceed the budget.
This is the lower-layer prerequisite for the exact creation receipt in #2536.

## Changes in detail

Use #2542's bounded command boundary for readiness reads. Every command and
polling wait consumes the same absolute deadline. Cleanup bounds the owned
local process; it does not prove remote effects stopped.
Final observation admission checks both expiry and the thread's interrupt flag.
Cancellation concurrent with a Running reply is refused rather than admitted.

Separate transport and resource projection from the executor record.
Compose the legacy acquisition order through named callbacks without nested conditions.
Named helpers project memory/CPU once for both resource sections. Defaults
move to packaged EDN without changing their values. Validate fixed projection
data before provider calls. Tests use factories,
intent-named budgets and programs, and genuine per-namespace strata.
Fixed projection defaults are not new operator options.
Existing task `:workdir`/`:resources` and executor `:kubectl-path` remain their override inputs.
Configurable timing options remain in the following receipt change, #2536.
Check cancellation before submission, cleanup and retention, and restore interrupts.
An interrupted readiness result cannot trigger name-only cleanup.
Already dispatched provider requests cannot be recalled by this local check.

The dependency graph is acyclic:

```text
executor -> {acquisition, client, resources, defaults}
acquisition -> {result, runtime.messages}
client -> {defaults, deadline, command}
resources -> defaults
defaults -> {config.interface, runtime.messages, Malli}
```

Job apply, creation identity, noncancelled failure cleanup, ordinary release and
execution remain legacy paths. This PR does not authorize or implement full stop.
The create-only request and confirmed Job identity remain scoped to #2536.

## Testing plan

Run readiness, resource projection, data refusal and #2542's command
fixtures in both JVM products, source Babashka and a fresh package.
The packet also checks constructor wiring and the legacy acquisition failure order.
Also refuse malformed projection data without altering unrelated resource loading.
All four modes pass 23 tests and 78 assertions each.
Use disposable CLI fixtures to hang listing or status and exceed the poll budget.
Mock late status and cancellation without sleeping test threads.
Count commands to prove cancellation during listing never dispatches status.
Pin the legacy resource defaults independently of the packaged data.
Removing the final interrupt guard fails one assertion without runtime errors
on both Java 21 and native Babashka.
Execute the five changed implementation namespaces' REPL examples on Java 21
and native Babashka. No live cluster calls are used.
Remove the unused internal template loader after tracing its lack of callers.
The YAML template remains the canonical documentation asset.

## Deployment plan

No deployment or cluster changes. The existing readiness defaults remain
120 seconds total and 2 seconds between polls, now bounded locally.

## Related issues/PRs

Follows merged #2542. Prepares the smaller exact-Job-receipt change in #2536.

[Pinned standards guide](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/agents.md)
and [catalog](https://github.com/miniforge-ai/miniforge-standards/blob/72f451d3d7e0ad82079610bec61be0399a2b7117/index.mdc).

## Checklist

- Verify all four local runtime modes, negative controls and strict lint.
- Review the actual diff against all applicable pinned standards before push.
- Require current-head Test, Build and a green full Copilot review.
- Resolve every thread and verify signing and fresh conflicts before merge.
